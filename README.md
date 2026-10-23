# NovelNest

A personal Android web novel reader, styled after the WebNovel and Wuxiaworld apps, with no shop, coins or ads.

**Install:** open https://github.com/mistajoe83/novel-reader/releases/latest on your phone, download the `.apk`, and open it. Allow "install unknown apps" for your browser when Android asks. New versions install over the old one and keep your library.

## Features
- Discover page with hot picks, rankings, genres and recommendations based on what you read
- Sources: NovelBin, FreeWebNovel, NovelFire, Royal Road, Scribble Hub, ReadNovelFull, NovelFull (each can be turned off or pointed at a new address)
- Library with update checks, reading history and bookmarks
- Reader with themes, fonts, spacing, endless scrolling into the next chapter, volume-key paging and listen (text to speech)
- Chapter downloads for offline reading, running in the background
- In-app private DNS (DNS-over-HTTPS), default dns.adguard.com, with an ordered list of fallback servers. Only the app uses it; phone settings are untouched.
- Source health & fixes: test every site, pass browser checks, change a site's address, switch a book to another site, and get fixes from [`sources/rules.json`](sources/rules.json) without a new app version. See [sources/README.md](sources/README.md).
- Backup and restore to a file
