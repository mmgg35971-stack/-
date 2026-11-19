# مترجم المانجا · Manga Translator

تطبيق Android يترجم المانجا والمانهوا والمانهوا الصينية والويب تون من الإنجليزية إلى العربية.
يقرأ النص من الصفحات (OCR)، ويترجمه بالذكاء الاصطناعي مع مراعاة السياق والقاموس، ثم يعرض
الترجمة العربية فوق الفقاعات الأصلية مع إمكانية المقارنة والتعديل.

## البنية

```
android/    تطبيق Kotlin + Jetpack Compose (MVVM, Hilt, Room, WorkManager, Coil)
backend/    Supabase: ترحيلات Postgres + RLS، وEdge Functions (Deno)، وسكربتات الاختبار
docs/       المعمارية، والمخطط، والواجهة، والإعداد، والخطة
```

## الخطط

| | Free | Pro | Premium |
|---|---|---|---|
| الفصول يومياً (UTC) | 10 | 100 | غير محدود (استخدام عادل) |

الحدود تُحتسب على الخادم فقط، وأكواد الهدايا تفعّل Pro أو Premium لمدة محددة.

## البدء

- الإعداد الكامل (Supabase، والمفاتيح، وGoogle Sign-In، وأول مدير، والتوقيع): [docs/setup/SETUP.md](docs/setup/SETUP.md)
- المعمارية والقرارات: [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md)
- قاعدة البيانات: [docs/database/SCHEMA.md](docs/database/SCHEMA.md) · الواجهة: [docs/api/API.md](docs/api/API.md)
- المراحل وما بقي: [docs/PLAN.md](docs/PLAN.md) · قيود بيئة البناء: [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md)

## الاختبارات

```bash
bash backend/scripts/test-db.sh
bash backend/scripts/test-functions.sh
cd android && ./gradlew testDebugUnitTest assembleDebug
```

## الأمان باختصار

لا توجد أي مفاتيح سرية داخل التطبيق. كل الجداول محمية بـ RLS، والقرارات التجارية (الحدود،
والأكواد، وصلاحية المدير) تتم في دوال Postgres ذرية لا يستدعيها إلا الخادم. الأكواد وأسرار
المدير مخزنة كـ HMAC فقط. التفاصيل في [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md#6-الأمان).

الخطوط (Cairo وIBM Plex Sans Arabic وNoto Sans Arabic) مرخصة بـ SIL OFL 1.1 ([docs/licenses](docs/licenses/OFL-fonts.txt)).
