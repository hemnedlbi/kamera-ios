# کامێرا — ئەپی ئایفۆن

ئەپی ئاگادارکردنەوەی کامێراکانی خێرایی (هەولێر و سۆران) بۆ ئایفۆن. ئەپەکە ماڵپەڕی
[camera.4chra.com](https://camera.4chra.com) دەکاتەوە، و GPSـی پشتەوە و ئاگادارکردنەوەی سیستەمی بۆ زیاد دەکات،
بۆیە کاتێک ئەپەکە لە پشتەوەیە یان شاشەکە کوژاوەیە هەر ئاگادارت دەکاتەوە.

- هەر گۆڕانکارییەک لە `main` دا، GitHub Actions فایلی `Kamera.ipa` دروست دەکات و لە **Releases → latest** دایدەنێت.
- ئەم IPA ـیە واژۆ نەکراوە؛ بە **AltStore** یان **SideStore** و Apple IDـی خۆت دایبەزێنە.
- Apple IDـی بێبەرامبەر: ئەپەکە هەر ٧ ڕۆژ جارێک پێویستی بە نوێکردنەوەیە (AltStore خۆکارانە دەیکات).

Design & Development Hemn A.Muhammad

## ئەندرۆید

هەمان ئەپ بۆ ئەندرۆیدیش لە بوخچەی `android/` دایە. کاتێک ئەپەکە لە پشتەوەیە یان شاشەکە کوژاوەیە،
`KameraGuard.java` (وەک `KameraGuard.swift` ی ئایفۆن) GPS دەخوێنێتەوە و ئاگادارکردنەوە و دەنگ دەنێرێت.

- هەر گۆڕانکارییەک، GitHub Actions فایلی `Kamera.apk` دروست دەکات: `main` → **Releases → android-latest**،
  لقەکانی تر → pre-release ـی `android-test-<ناوی لق>`. ڕاستەوخۆ لەسەر مۆبایل دای بەزێنە.
- بۆ Google Play: کاتێک کلیلی واژۆ (upload key) لە GitHub Secrets دانرا
  (`KAMERA_KEYSTORE_B64`، `KAMERA_KEYSTORE_PASSWORD`، `KAMERA_KEY_ALIAS`، `KAMERA_KEY_PASSWORD`)،
  فایلی واژۆکراوی `Kamera.aab` لە Artifacts ی هەمان build دا دروست دەبێت.
