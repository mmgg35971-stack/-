# واجهة الخادم (Edge Functions)

كل الدوال على `POST {SUPABASE_URL}/functions/v1/<name>` مع `Authorization: Bearer <JWT>`
وجسم JSON، ما عدا `jobs-worker` الذي يتطلب `x-worker-secret`.
الخطأ دائماً بهذا الشكل: `{"error": "<code>", "message": "...", ...extras}`.

| الدالة | الجسم | النتيجة |
|---|---|---|
| `me` | — | `{profile, entitlements}` (الخطة، والحد، والمستخدم اليوم، و`resets_at`، والمزايا، و`is_admin`) |
| `jobs-create` | `{idempotency_key, chapter_title, chapter_number?, work_id?, quality?, pages:[{mime_type, byte_size}]}` | `{job, pages:[{id, page_index, original_path}], reused}`. نفس المفتاح يعيد نفس المهمة. |
| `jobs-start` | `{job_id}` | `{ok, already_counted, status, usage}`. هنا يُحتسب الفصل. |
| `jobs-retry-page` | `{page_id}` | `{ok}`. لا يحتسب فصلاً جديداً. |
| `jobs-worker` | — (cron) | يعالج الطابور لمدة تصل إلى 110 ثوانٍ ثم يستدعي نفسه إذا بقي عمل |
| `gift-codes-redeem` | `{code}` | `{ok, plan_id, starts_at, expires_at, duration_days}` |
| `admin-activate` | `{code}` | `{ok}` |
| `admin` | `{action, ...}` | انظر أدناه |
| `account-delete` | `{confirm: "DELETE"}` | `{ok}` |

### إجراءات `admin`

| action | المعاملات | النتيجة |
|---|---|---|
| `stats` | — | إحصاءات حقيقية (المستخدمون حسب الخطة، والفصول، والفشل، والأكواد، وتكلفة AI اليوم، وتنبيه التكلفة) |
| `users_search` | `query, limit, offset` | `{users}` (بحث بالبريد أو الاسم أو المعرّف) |
| `user_detail` | `user_id` | الملف، والصلاحيات، والاشتراكات، وسجل الاستخدام |
| `gift_codes_create` | `plan, duration_days, quantity (≤1000), max_redemptions, code_expires_at?, notes?` | `{batch_id, codes:[...]}`. **هذه المرة الوحيدة التي تظهر فيها الأكواد.** |
| `gift_codes_list` | `plan?, status?, limit, offset` | `{codes, total}` (التلميح فقط، مع من استخدم الكود) |
| `gift_code_revoke` | `code_id` | `{ok}` |
| `audit_logs` | `limit, offset` | `{logs}` |

## رموز الأخطاء

| الرمز | HTTP | المعنى |
|---|---|---|
| `unauthenticated` / `session_expired` | 401 | لا جلسة أو انتهت |
| `forbidden` | 403 | ليس مديراً أو لا يملك المورد |
| `account_inactive` | 403 | الحساب موقوف |
| `invalid_input` / `invalid_json` / `payload_too_large` | 400 / 413 | مدخلات خاطئة |
| `no_pages` / `too_many_pages` | 400 | عدد الصفحات (الحد في `max_pages`) |
| `uploads_incomplete` | 409 | لم تُرفع كل الصفحات إلى Storage |
| `job_busy` / `restart_required` / `page_not_failed` / `duplicate_request` | 409 | حالة لا تسمح بالعملية |
| `daily_limit_reached` | 429 | انتهى الحد اليومي (مع `daily_limit` و`resets_at`) |
| `fair_use_review` | 429 | Premium: نشاط غير طبيعي يحتاج مراجعة |
| `quality_not_in_plan` | 403 | الجودة المطلوبة غير متاحة في الخطة |
| `rate_limited` | 429 | محاولات كثيرة |
| `invalid_code` / `code_already_used` / `code_expired` / `code_revoked` / `higher_plan_active` | 400 / 409 | أكواد الهدايا |
| `provider_not_configured` / `server_not_configured` | 503 | مفاتيح OCR أو الترجمة أو الأسرار غير مضبوطة على الخادم |
| `job_not_found` / `page_not_found` / `user_not_found` / `work_not_found` | 404 | غير موجود |

## الوصول المباشر (PostgREST)

يقرأ التطبيق ويعدّل مباشرة عبر `/rest/v1` ما تسمح به RLS فقط: `translation_history` و`user_library`
و`translation_pages` و`translation_segments` (تعديل `user_edited_text` و`style`) و`glossary_entries`
و`user_settings` و`notifications` و`subscription_plans`. الصور تُقرأ من `/storage/v1/object/authenticated/chapter-pages/...`.
