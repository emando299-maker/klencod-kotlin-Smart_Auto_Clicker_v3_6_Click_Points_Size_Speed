# Smart Auto Clicker

مشروع Android Kotlin أُنشئ بـ KlencodIDE.

## القالب: لوحة رسم بسيطة 🎨

### الميزات
- رسم بالإصبع (Canvas + Path)
- 8 ألوان + 3 سماكات فرشاة
- تراجع (Undo) + مسح الكل
- حفظ PNG في Pictures/KlencodDrawings (Android 10+)
- كل المنطق في ملف MainActivity.kt واحد
- لا مكتبات خارجية (فقط kotlin-stdlib)

### البناء

يتم البناء تلقائياً على GitHub Actions عند الضغط على "بناء APK" من داخل التطبيق.

### الهيكل
- `app/src/main/java/` — كود Kotlin (MainActivity.kt فقط)
- `app/src/main/res/` — الموارد (strings.xml + app_icon.png)
- `app/src/main/AndroidManifest.xml` — بيان التطبيق
- `app/module.toml` — إعدادات الوحدة

### التوسيعات المقترحة
- 🧹 ممحاة (Eraser)
- ↪️ إعادة (Redo)
- 📤 مشاركة الصورة
- 🎨 منتقي ألوان HSL
- 🌐 شبكة خلفية

### Kotlin idiom

هذا القالب يستخدم:
- `data class` للـ Stroke
- `when` بدل `switch`
- `?.` و `?:` لـ Null safety
- `mutableListOf` بدل `ArrayList`
- Top-level functions داخل `object`


## Version 2.4
- Added five independent word-search slots with individual enable/disable checkboxes.
- Searches enabled words sequentially; the service waits on the current word until it appears and a click succeeds, then advances to the next enabled word. The sequence repeats after the last enabled slot.
- Keeps the floating-window close/reopen behavior from the previous version.


## Version 2.7
- Added per-slot image target selection from Android's document picker.
- Each enabled slot can use a word, an image, or both (image match first, OCR fallback when a word is also configured).
- Image matching runs against accessibility screenshots on Android 11+ and clicks the detected image center when the visual match confidence passes the threshold.
- Image-only slots are supported in the sequential workflow.
- Build not verified in this environment because Gradle distribution download requires network access.


### v2.7 scan-loop fixes
- Automatically retries screen capture and target matching after misses, so games with few accessibility events do not stall.
- Schedules the next scan after successful accessibility-node clicks and after the click delay.
- Shows clearer status messages for image misses and gesture failures.
- Expands image target scale search for smaller/larger on-screen targets.


## الإصدار 2.8 — تحديد هدف من الشاشة
- من التطبيق الرئيسي، اضغط «تحديد جزء من الشاشة لهذه الخانة» للخانة المطلوبة.
- ارجع إلى اللعبة أو التطبيق المراد النقر داخله.
- من النافذة العائمة اضغط «تحديد هدف من الشاشة».
- اسحب مستطيلاً حول الهدف في لقطة الشاشة ثم اضغط «حفظ الهدف».
- الهدف المحفوظ يضاف للخانة المحددة ويبدأ الفحص عند تشغيل النقر التلقائي.

ملاحظة: لم يمكن بناء APK في بيئة العمل لأن Gradle يحتاج تنزيل التوزيعة من الإنترنت، وكان الاتصال بالخادم غير متاح. يجب بناء المشروع واختباره على جهاز Android فعلي قبل اعتبار الميزة مؤكدة.


## v2.9 fix
- Declares the Android accessibility screenshot capability (`canTakeScreenshot`) required by the screenshot-based target selection flow.
- Shows a more specific error message when Android rejects a screenshot request.

## Smart recognition mode (v3.4)

- Adds an optional `التعرّف الذكي المحلي` setting, enabled by default.
- Reuses on-device ML Kit text recognition and expands common Arabic/English action phrases (start/play, continue/next, back, close/exit, retry/restart, buy/shop, search, claim/reward) into matching visible labels.
- No API key, account, or network request is needed for this mode.
- This is lightweight local intent/keyword matching, not a general-purpose generative AI model. It cannot reliably understand arbitrary visual scenes or infer every action.
- Exact text matching remains supported; disable smart recognition to return to exact matching only.

Build note: this source archive was not compiled in the current environment because Gradle 8.14 could not be downloaded (`services.gradle.org` was unreachable). Install/build and test on a connected development machine before installing it on a phone.


## Version 3.5 — text-only target search

- Removed image picking and screen-region target selection from the UI and service.
- Search slots now require a word or phrase only.
- The service checks accessibility text first, then uses on-device ML Kit Latin-script OCR on Android 11+ screenshots.
- The OCR does not reliably recognize Arabic text drawn inside game canvases; Arabic text exposed through Android accessibility nodes may still be searchable. This is local OCR/rule matching, not a general-purpose AI model.


Important limitation: the bundled ML Kit recognizer is Latin-script OCR. Arabic text exposed as accessibility node text can be matched, but Arabic text rendered only as pixels in a game canvas is not guaranteed to be recognized by this version.

## v3.6 additions
- Added five configurable coordinate-based click points (X/Y in screen pixels).
- Added point mode to run enabled points sequentially instead of text search.
- Added adjustable on-screen point marker size (12–56 dp); markers are non-touchable overlays.
- Kept click interval control (1–5000 ms) for pacing between actions.
- Build was not verified in this environment because Gradle distribution download requires network access.
