# Fixing a broken source

Sites change their layout, move to new addresses or add browser checks. NovelNest has four ways to deal with that, from easiest to hardest.

## 1. In the app (no GitHub needed)

Open **More → Source health & fixes**.

- **Test all sources** runs each site through: book lists, search, book page, chapter list and chapter text. A red step shows what broke.
- **Pass site check**: when a site shows "checking your browser" (Cloudflare), open it, wait until the real site appears, then tap **Done**. The app reuses that pass.
- **Change address**: when a site moves to a new domain (for example `novelbin.com` → `novelbin.me`).
- **Switch source**: on a book page, tap **⋮ → Switch source** and pick the same book on another site. Your library place, read chapters, last position and bookmarks move with it.

## 2. Fix file on GitHub (no new app version)

The app downloads [`sources/rules.json`](rules.json) from this repo every 6 hours, or straight away with **Check for fixes**. Editing that file on GitHub fixes every phone running the app. Anything you leave out keeps using the code built into the app.

Source ids: `novelbin`, `freewebnovel`, `novelfire`, `royalroad`, `scribblehub`, `readnovelfull`, `novelfull`.

Example with every field (all optional):

```json
{
  "version": 1,
  "sources": {
    "novelbin": {
      "baseUrl": "https://novelbin.me",
      "notice": "NovelBin moved to novelbin.me",
      "disabled": false,
      "headers": { "Referer": "https://novelbin.me/" },

      "urls": {
        "listing": "{base}/sort/{listing}?page={page}",
        "genre":   "{base}/genre/{genre}?page={page}",
        "search":  "{base}/search?keyword={query}&page={page}",
        "details": "{base}{path}",
        "chapters": "{base}/ajax/chapter-archive?novelId={slug}"
      },
      "list": {
        "item": ".list-novel .row",
        "link": "h3 a",
        "title": "h3 a",
        "cover": "img",
        "subtitle": ".author",
        "next": "li.next:not(.disabled)"
      },
      "details": {
        "title": "h3.title",
        "cover": ".book img",
        "description": ".desc-text",
        "author": ".info a[href*=author]",
        "genres": ".info a[href*=genre]",
        "status": ".info a[href*=status]"
      },
      "chapters": { "item": "ul.list-chapter li a", "reversed": false },
      "chapter": {
        "content": "#chr-content",
        "title": ".chr-title",
        "remove": [".ads", "script", "div[id^=pf-]"],
        "dropParagraphs": ["novelbin", "read latest chapters at"]
      },
      "coverRewrite": [ { "from": "images\\.novelbin\\.com/novel_\\d+_\\d+/", "to": "novelfire.net/server-1/" } ]
    }
  }
}
```

What each part does:

| Field | Use it when |
| --- | --- |
| `baseUrl` | The site moved to a new domain. |
| `disabled`, `notice` | The site is gone or unusable. The notice is shown in the app. |
| `headers` | The site needs a different header, such as `Referer`. |
| `urls` + `list` | Lists, genres or search stopped working. `list` is only used for the address patterns given in `urls` (`listing`, `genre`, `search`). Placeholders: `{base}`, `{page}`, `{query}`, `{listing}`, `{genre}`. |
| `details` | The book page shows the wrong title, cover or description. Fields left out come from the built-in code. |
| `chapters` | The chapter list is empty or wrong. `urls.chapters` can point at another page; placeholders `{path}` and `{slug}` (last part of the book's address). |
| `chapter` | Chapter text is missing (`content`), or has ads or junk lines (`remove`, `dropParagraphs`, which are case-insensitive regular expressions). |
| `coverRewrite` | Covers don't load. Each `from` is a regular expression replaced by `to`. |

Selectors are CSS selectors (the same ones used in a browser's developer tools). To find them, open the page in Chrome on a computer, right-click the part you want, choose **Inspect**, and note its tag and class.

To test a fix before publishing it, paste just that source's part (the object after `"novelbin":`) into **Source health → Edit fix** on the phone. It applies only on that phone and wins over the GitHub file. **Remove my fix** goes back to the GitHub one.

## 3. Code change (new app version)

When a site needs logic a rule can't express, change the source in `core/src/main/kotlin/com/joe/novelreader/core/source/`. Pushing to `main` runs live checks against every site (results in `live-report.md` on the `ci-results` branch); a commit message containing `[release]` publishes a new APK.
