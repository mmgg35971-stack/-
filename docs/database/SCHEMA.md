# مخطط قاعدة البيانات

المصدر الحقيقي هو ملفات `backend/supabase/migrations/`، وهذا المستند ملخص لها.
كل الجداول في schema `public` وعليها RLS. عمود "العميل" يوضح ما يستطيع المستخدم المسجل فعله مباشرة.

## الجداول

| الجدول | الغرض | العميل |
|---|---|---|
| `app_config` | إعدادات عامة: `fair_use` و`ai_cost_alerts` و`max_pages_per_chapter` (200) و`max_page_bytes` (15MB) | لا وصول |
| `subscription_plans` | free / pro / premium: الرتبة، والحد اليومي (10 / 100 / null)، والأولوية (10 / 50 / 100)، والمزايا (jsonb)، ومعرّف منتج Play | قراءة |
| `user_profiles` | ملف المستخدم، يُنشأ تلقائياً عند التسجيل (`handle_new_user`) | قراءة صفه، وتعديل `display_name` و`avatar_url` فقط |
| `user_settings` | إعدادات القراءة والترجمة والتطبيق، مع قيود CHECK على القيم | قراءة وتعديل أعمدة التفضيلات |
| `subscriptions` | اشتراكات المستخدم: المصدر (`gift_code` / `google_play` / `admin`)، والحالة، والبداية والانتهاء | قراءة صفوفه |
| `daily_usage` | (user, usage_date UTC) → `chapters_used` | قراءة |
| `user_library` | الأعمال (مانجا/مانهوا...) والمفضلة وآخر قراءة | CRUD لصفوفه |
| `translation_jobs` | فصل واحد: الحالة، وعدد الصفحات، و`idempotency_key` (فريد لكل مستخدم)، و`usage_counted` و`usage_refunded` و`usage_date` | قراءة، وتعديل العنوان والرقم والعمل، وحذف إذا لم تكن قيد التشغيل |
| `translation_pages` | الصفحات: المسار الأصلي، والأبعاد، والحالة، والمحاولات | قراءة |
| `translation_segments` | مقاطع النص: مربع مُطبّع (0..1)، والنص الأصلي، والثقة، و`needs_review`، و`translated_text`، و`user_edited_text`، و`style` | قراءة، وتعديل `user_edited_text` و`style` و`category` و`reported_bad` فقط |
| `translation_history` (view) | عرض مسطح للسجل مع عنوان العمل والغلاف، `security_invoker` | قراءة |
| `glossary_entries` | قاموس المستخدم (اسم، مكان، مصطلح...)، فريد على (user, work, lower(term)) | CRUD |
| `work_context` | ملخص سياق القصة لكل عمل | CRUD |
| `gift_code_batches` / `gift_codes` / `gift_code_redemptions` | دفعات وأكواد (hash + hint) واستخدامات (فريد لكل كود ومستخدم) | لا وصول |
| `admin_activation_secrets` / `admin_roles` | أسرار التفعيل (hash) وأدوار المدير | لا وصول |
| `admin_audit_logs` | سجل للإضافة فقط (trigger يمنع UPDATE وDELETE) | لا وصول |
| `notifications` | إشعارات داخل التطبيق | قراءة، وتعديل `read_at` |
| `ai_usage_events` | تكلفة كل طلب AI ونتيجته، لتنبيهات التكلفة | لا وصول |
| `rate_limit_buckets` | عدادات حد المعدل | لا وصول |
| `translation_reports` | بلاغات "ترجمة سيئة" | إضافة |

## الدوال

كلها `SECURITY DEFINER` وتنفيذها لـ `service_role` فقط، ما عدا `my_entitlements()` المتاحة لـ `authenticated`.

| الدالة | الوظيفة |
|---|---|
| `utc_today()` / `effective_plan(user)` / `usage_status(user)` / `my_entitlements()` | اليوم بتوقيت UTC، والخطة الفعالة، وحالة الاستخدام |
| `start_translation_job(user, job)` | يقفل صف الاستخدام، ويتحقق من الحد والجودة وعدد الصفحات، ويحتسب الفصل مرة واحدة، ويضع المهمة في الطابور |
| `refund_translation_job(job)` | استرداد عند الفشل الكامل (مرة واحدة) |
| `claim_next_job()` | سحب المهمة التالية حسب الأولوية بـ `SKIP LOCKED` |
| `finalize_translation_job(job)` | يحسب الحالة النهائية والاسترداد |
| `worker_save_page_ocr` / `worker_apply_translations` | كتابة نتائج OCR والترجمة دون المساس بـ `user_edited_text` |
| `redeem_gift_code(user, hash)` | التحقق، والتمديد أو البدء، ورفض الخطة الأقل، مع قفل استشاري |
| `admin_create_gift_codes` / `admin_revoke_gift_code` | إنشاء دفعة (hash فقط) وإلغاء كود، مع تسجيل في السجل |
| `activate_admin(user, hash)` | منح الدور إذا طابق السر، مع تسجيل |
| `admin_overview_stats()` / `admin_search_users(q)` | إحصاءات حقيقية وبحث |
| `expire_subscriptions()` | إنهاء الاشتراكات المنتهية (عبر pg_cron) |
| `rate_limit_hit(key, window, max)` | حد معدل بنافذة ثابتة |

## Storage

bucket خاص `chapter-pages`. المسار `{uid}/{job}/original/NNNN.ext` (والغلاف في `{uid}/cover/...`).
السياسات تسمح للمالك فقط بالقراءة والرفع (في مجلدي `original` و`cover`) والحذف داخل مجلده.

## الاختبارات

`backend/scripts/test-db.sh` يشغّل كل الترحيلات على Postgres مؤقت، ثم:
`tests/10_business_rules.sql` (التسجيل، والحد المجاني، وidempotency، والاسترداد، وبوابات الخطط،
وتفعيل المدير وRBAC، والأكواد، وتعارض الخطط، وانتهاء الاشتراك، وRLS والصلاحيات، ووصول anon)،
ثم اختباري تزامن: 20 استخداماً متزامناً لكود واحد تعطي نجاحاً واحداً، و15 بدءاً متزامناً
على حساب Free تعطي 10 محتسبة بالضبط.
