"""Builds one merged lineup in Dispatcharr from many providers.

Streams that are the same channel (same tvg-id or same cleaned-up name) across providers
become ONE channel with every provider's stream attached as backups, ordered by each
provider's free capacity. Safe to re-run: it only touches channels in the groups it owns.

Run (inside the Dispatcharr container):
  docker exec -i -e CURATE_CONFIG="$(cat lineup.json)" [-e CURATE_APPLY=1] [-e CURATE_SYNC_LIMITS=1] \
      dispatcharr python manage.py shell < curate.py
"""
import functools, json, os, re, requests
from collections import defaultdict
from django.db import transaction
from apps.channels.models import Channel, ChannelGroup, ChannelStream, Stream
from apps.epg.models import EPGData
from apps.m3u.models import M3UAccount

cfg = json.loads(os.environ.get("CURATE_CONFIG") or "{}")
APPLY = os.environ.get("CURATE_APPLY") == "1"
JUNK = re.compile(r"\b(hd|fhd|uhd|4k|sd|hevc|h265|lb|hb|backup|alt|alternative|\(.*?\)|\[.*?\])\b", re.I)
PREFIX = re.compile(r"^\s*(us|usa|united states|uk|ca|au)\s*[:|\-]?\s+", re.I)
# Country code prefixes: "DE: RTL" on streams, "AR | beIN" on groups. Streams only count "XX:" so US
# state locals like "IL | Springfield | CBS" aren't mistaken for Israel.
CODE = re.compile(r"^\s*([A-Z]{2,4})\s*:")
GROUP_CODE = re.compile(r"^\s*([A-Z]{2,4})\s*[:|]")
COUNTRY_CODES = set("""AD AE AF AL AM AO AR AT AU AZ BA BD BE BG BH BO BR BY CA CH CL CN CO CR CU CY CZ DE DK DO DZ
EC EE EG ES ET FI FR GB GE GH GR GT HK HN HR HT HU ID IE IL IN IQ IR IS IT JM JO JP KE KR KW KZ LB LK LT LU LV LY MA MK
MT MX MY NG NI NL NO NP NZ OM PA PE PH PK PL PR PS PT PY QA RO RS RU SA SE SG SI SK SN SV SY TH TN TR TW TZ UA UG UK US
UY UZ VE VN YE ZA USA GER FRA ESP ITA POR POL NED SWE NOR DEN FIN TUR ARA RUS BRA ARG MEX IND PAK EXYU AFR CAN AUS""".split())
ALLOWED = {c.strip().upper() for c in (cfg.get("countries") or "").split("|") if c.strip()}
EMPTY_EVENT = re.compile(r"no events?\b|no games?\b|off ?air|^[^:]*:\s*$|\(209\d", re.I)


def foreign(name, group=False):
    """True when the name starts with another country's code and a countries list is set."""
    m = (GROUP_CODE if group else CODE).match(name or "") if ALLOWED else None
    return bool(m) and m.group(1) in COUNTRY_CODES and m.group(1) not in ALLOWED


def sync_limits():
    for a in M3UAccount.objects.filter(account_type="XC", is_active=True):
        try:
            u = requests.get(a.server_url.rstrip("/") + "/player_api.php",
                             params={"username": a.username, "password": a.password}, timeout=20).json().get("user_info", {})
            n = int(u.get("max_connections") or 0)
            if n and n != a.max_streams:
                print(f"limit  {a.name}: {a.max_streams} -> {n}")
                if APPLY:
                    a.max_streams = n; a.save(update_fields=["max_streams"])
            else:
                print(f"limit  {a.name}: {a.max_streams} (provider says {n or '?'})")
        except Exception as e:
            print(f"limit  {a.name}: could not read ({type(e).__name__})")


def name_key(name):
    n = PREFIX.sub("", name or "")
    n = JUNK.sub(" ", n)
    return re.sub(r"[^a-z0-9]+", "", n.lower())


def tvg_key(t):
    t = (t or "").strip().lower()
    return "" if not t or "dummy" in t or t in ("none", "null") else t


CLAIMED = set()
SKIP_GROUPS = re.compile(cfg["skip_groups"], re.I) if cfg.get("skip_groups") else None


def natural(n):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", n.lower())]


def claim(spec, all_groups):
    """The provider groups this lineup group takes, skipping ones an earlier group already took."""
    pats = [re.compile(p, re.I) for p in spec["sources"]]
    skip = re.compile(spec["skip_groups"], re.I) if spec.get("skip_groups") else None
    own = {spec["name"] for spec in cfg.get("groups", [])}
    any_country = spec.get("any_country")
    groups = [g for g in all_groups
              if g.id not in CLAIMED and (g.name not in own or g.name == spec["name"]) and any(p.search(g.name) for p in pats)
              and not (SKIP_GROUPS and SKIP_GROUPS.search(g.name)) and not (skip and skip.search(g.name))
              and (any_country or not foreign(g.name, group=True))]
    CLAIMED.update(g.id for g in groups)
    return groups


def build_group(spec, rank, all_groups):
    exclude = re.compile(spec["exclude"], re.I) if spec.get("exclude") else None
    groups = claim(spec, all_groups)
    streams = list(Stream.objects.filter(channel_group__in=groups, m3u_account__is_active=True, is_stale=False)
                   .select_related("m3u_account"))
    if not spec.get("any_country"):
        streams = [s for s in streams if not foreign(s.name)]
    if cfg.get("drop_empty_events") and spec.get("merge") is False:
        streams = [s for s in streams if not EMPTY_EVENT.search(s.name or "")]
    if exclude:
        streams = [s for s in streams if not exclude.search(s.name or "")]
    if spec.get("include"):
        inc = re.compile(spec["include"], re.I)
        streams = [s for s in streams if inc.search(s.name or "")]
    # union streams sharing a tvg-id or a cleaned name
    parent = list(range(len(streams)))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]; i = parent[i]
        return i
    seen = {}
    merge = spec.get("merge", True)
    for i, s in enumerate(streams):
        if not merge:
            continue
        for k in filter(None, ("t:" + tvg_key(s.tvg_id) if tvg_key(s.tvg_id) else "", "n:" + name_key(s.name))):
            if k in seen:
                parent[find(i)] = find(seen[k])
            else:
                seen[k] = i
    clusters = defaultdict(list)
    for i, s in enumerate(streams):
        clusters[find(i)].append(s)
    out = []
    min_providers = spec.get("min_providers", 1)
    for members in clusters.values():
        if len({s.m3u_account_id for s in members}) < min_providers:
            continue
        members.sort(key=lambda s: (rank.get(s.m3u_account_id, 99), s.id))
        names = sorted({PREFIX.sub("", s.name).strip() for s in members}, key=lambda n: (len(n), n))
        tvg = next((s.tvg_id for s in members if tvg_key(s.tvg_id)), "")
        out.append({"name": names[0], "tvg_id": tvg, "logo": next((s.logo_url for s in members if s.logo_url), ""), "streams": members})
    out.sort(key=lambda c: natural(c["name"]))
    return [g.name for g in groups], out


EPG_BY_ID = {}


def epg_for(tvg):
    if not tvg_key(tvg):
        return None
    if not EPG_BY_ID:
        for e in EPGData.objects.exclude(tvg_id=None).order_by("-id"):
            EPG_BY_ID[e.tvg_id.lower()] = e
    return EPG_BY_ID.get(tvg.lower())


@functools.lru_cache(maxsize=None)
def logo_for(url):
    if not url:
        return None
    try:
        from apps.channels.models import Logo
        return Logo.objects.get_or_create(url=url, defaults={"name": url.rsplit("/", 1)[-1][:200]})[0]
    except Exception:
        return None


def hidden():
    """Lists provider groups the lineup leaves out, so you can add any of them back."""
    from django.db.models import Count
    all_groups = list(ChannelGroup.objects.all())
    for spec in cfg.get("groups", []):
        claim(spec, all_groups)
    own = {spec["name"] for spec in cfg.get("groups", [])}
    counts = {r["channel_group"]: r["n"] for r in Stream.objects.filter(m3u_account__is_active=True, is_stale=False)
              .values("channel_group").annotate(n=Count("id"))}
    rows = [(g.name, counts[g.id]) for g in all_groups if g.id not in CLAIMED and g.name not in own and counts.get(g.id)]
    for name, n in sorted(rows, key=lambda r: natural(r[0])):
        why = "skipped" if SKIP_GROUPS and SKIP_GROUPS.search(name) else "other country" if foreign(name, group=True) else "no group"
        print(f"hidden {n:>6}  {name}  ({why})")
    print(f"hidden: {len(rows)} provider groups, {sum(n for _, n in rows)} streams. Add one with a group like "
          '{"name": "...", "start": 80000, "any_country": true, "sources": ["^exact group name$"]}')


def main():
    if os.environ.get("CURATE_HIDDEN") == "1":
        return hidden()
    if os.environ.get("CURATE_SYNC_LIMITS") == "1":
        sync_limits()
    accts = list(M3UAccount.objects.filter(is_active=True))
    accts.sort(key=lambda a: (-(a.max_streams or 0), -(getattr(a, "priority", 0) or 0), a.id))
    rank = {a.id: i for i, a in enumerate(accts)}
    print("provider order:", ", ".join(f"{a.name}({a.max_streams})" for a in accts))
    total = 0
    all_groups = list(ChannelGroup.objects.all())
    for spec in cfg.get("groups", []):
        src, chans = build_group(spec, rank, all_groups)
        epg_hits = sum(1 for c in chans if epg_for(c["tvg_id"]))
        print(f"\n== {spec['name']}: {len(chans)} channels from {len(src)} source groups, guide data for {epg_hits}")
        for g in src:
            print(f"   source: {g}")
        for c in chans[: spec.get("preview", 5)]:
            print(f"   {c['name']:<40} {len(c['streams'])} streams  tvg={c['tvg_id'] or '-'}")
        total += len(chans)
        if not APPLY:
            continue
        with transaction.atomic():
            group, _ = ChannelGroup.objects.get_or_create(name=spec["name"])
            existing = {cs.stream_id: cs.channel for cs in ChannelStream.objects.filter(channel__channel_group=group, order=0).select_related("channel")}
            num = float(spec.get("start", 1000))
            rows, new, old = [], [], []
            for c in chans:
                ch = existing.pop(c["streams"][0].id, None) or Channel(channel_group=group)
                ch.name, ch.tvg_id, ch.channel_number = c["name"], c["tvg_id"] or None, num
                ch.epg_data = epg_for(c["tvg_id"]) or ch.epg_data
                ch.logo = logo_for(c["logo"]) or ch.logo
                (old if ch.pk else new).append(ch)
                rows.append((ch, c))
                num += 1
            Channel.objects.bulk_create(new, batch_size=1000)
            Channel.objects.bulk_update(old, ["name", "tvg_id", "channel_number", "epg_data", "logo"], batch_size=1000)
            ChannelStream.objects.filter(channel__channel_group=group).delete()
            ChannelStream.objects.bulk_create(
                [ChannelStream(channel=ch, stream=st, order=i) for ch, c in rows for i, st in enumerate(c["streams"])],
                batch_size=5000)
            keep = {ch.id for ch, _ in rows}
            gone = Channel.objects.filter(channel_group=group).exclude(id__in=keep)
            if gone.exists():
                print(f"   removing {gone.count()} channels no longer offered")
                gone.delete()
    print(f"\n{'APPLIED' if APPLY else 'DRY RUN'}: {total} channels")


main()
