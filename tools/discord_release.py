#!/usr/bin/env python3
"""Post a published NewTube release to #announcements on the NewTube Discord server.

The post is one embed: the release name linked to its page, the "What's new" section of the
GitHub release notes as written, and where to get it. The webhook URL is a secret
(DISCORD_RELEASES_WEBHOOK); discord.yml runs this when a release is published. Locally:

    tools/discord_release.py v1.12.0 --dry-run    # print the payload, post nothing
    DISCORD_RELEASES_WEBHOOK=... tools/discord_release.py v1.12.0
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request

REPO = "aleixrodriala/newtube"
COLOR = 0x1F2A78  # the launcher icon's navy
MAX_DESCRIPTION = 4096  # Discord's limit for an embed description
FOOTER = "Unofficial client, not affiliated with Google, YouTube or SmartTube's developer"


def whats_new(body):
    """The bullets under "### What's new", up to the next heading or HTML comment."""
    m = re.search(r"^###\s*What's new\s*$(.*?)(?=^#{1,3}\s|^<!--|\Z)", body, re.M | re.S)
    return m.group(1).strip() if m else ""


def description(release):
    tag = release["tagName"]
    news = whats_new(release["body"])
    tail = (f"**Download:** [{tag} on GitHub]({release['url']}). Most phones want "
            f"`NewTube_{tag.lstrip('v')}_arm64-v8a.apk`. Already have NewTube? "
            "It updates from inside the app, in the You tab.")
    room = MAX_DESCRIPTION - len(tail) - 2
    if len(news) > room:
        # Cut at a bullet boundary so no link or bold run is left open.
        cut = news.rfind("\n- ", 0, room - 4)
        news = (news[:cut] if cut > 0 else "") + "\n- …"
    return f"{news}\n\n{tail}".strip()


def payload(release):
    return {
        "embeds": [{
            "title": release["name"] or f"NewTube {release['tagName'].lstrip('v')}",
            "url": release["url"],
            "description": description(release),
            "color": COLOR,
            "footer": {"text": FOOTER},
            "timestamp": release["publishedAt"],
        }],
        "allowed_mentions": {"parse": []},
    }


def fetch_release(tag):
    out = subprocess.run(
        ["gh", "release", "view", tag, "-R", os.environ.get("GH_REPO", REPO),
         "--json", "tagName,name,url,body,publishedAt,isDraft,isPrerelease"],
        check=True, capture_output=True, text=True).stdout
    release = json.loads(out)
    if release["isDraft"] or release["isPrerelease"]:
        sys.exit(f"{tag} is a draft or a pre-release; only published releases are announced.")
    return release


def post(webhook, data):
    req = urllib.request.Request(
        webhook + "?wait=true", data=json.dumps(data).encode(), method="POST",
        headers={"Content-Type": "application/json",
                 # Discord refuses urllib's default user agent.
                 "User-Agent": f"NewTubeReleaseBot (https://github.com/{REPO}, 1)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["id"]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("tag", help="published release tag, e.g. v1.12.0")
    ap.add_argument("--dry-run", action="store_true", help="print the payload instead of posting")
    args = ap.parse_args()
    if not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag):
        sys.exit(f"{args.tag} is not a version tag.")
    data = payload(fetch_release(args.tag))
    if args.dry_run:
        print(json.dumps(data, indent=2, ensure_ascii=False))
        return
    webhook = os.environ.get("DISCORD_RELEASES_WEBHOOK", "").strip()
    if not webhook:
        sys.exit("DISCORD_RELEASES_WEBHOOK is not set.")
    print(f"Posted {args.tag} to Discord (message {post(webhook, data)}).")


if __name__ == "__main__":
    main()
