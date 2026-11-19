# التشغيل والإعداد

هذه الخطوات تحتاج حساباتك ومفاتيحك، ولا يمكن تنفيذها من داخل المستودع.

## 1. مشروع Supabase

1. أنشئ مشروعاً على https://supabase.com واحتفظ بـ `Project URL` و`anon key`.
2. ثبّت Supabase CLI، ثم من مجلد `backend/`:
   ```bash
   supabase link --project-ref <ref>
   supabase db push                 # يطبق كل الملفات في supabase/migrations
   supabase functions deploy        # ينشر كل Edge Functions
   ```
3. الأسرار (لا تُحفظ في Git أبداً):
   ```bash
   cp supabase/.env.example supabase/.env   # ثم املأ القيم
   supabase secrets set --env-file supabase/.env
   ```
   - `ANTHROPIC_API_KEY`: من https://console.anthropic.com
   - `GOOGLE_VISION_API_KEY`: من Google Cloud بعد تفعيل Cloud Vision API، ويُفضّل تقييد المفتاح بهذه الواجهة فقط.
   - `GIFT_CODE_PEPPER` و`ADMIN_SECRET_PEPPER` و`WORKER_SECRET`: قيم عشوائية، مثلاً `openssl rand -base64 32`.
     تغيير `GIFT_CODE_PEPPER` لاحقاً يبطل الأكواد غير المستخدمة.

   بدون مفاتيح OCR والترجمة يعمل كل شيء آخر، و`jobs-start` يرفض بـ `provider_not_configured`
   **قبل** احتساب الفصل.

### بديل بدون كمبيوتر: النشر من GitHub

ملف `.github/workflows/deploy-backend.yml` يطبّق الترحيلات وينشر الدوال ويضبط الأسرار.
في GitHub: Settings → Secrets and variables → Actions → New repository secret، أضف:

| السر | مطلوب | من أين |
|---|---|---|
| `SUPABASE_ACCESS_TOKEN` | نعم | supabase.com → Account → Access Tokens (يبدأ بـ `sbp_`) |
| `SUPABASE_DB_PASSWORD` | نعم | كلمة مرور قاعدة البيانات التي اخترتها عند إنشاء المشروع |
| `ANTHROPIC_API_KEY` | للترجمة | console.anthropic.com |
| `GOOGLE_VISION_API_KEY` | لـ OCR | Google Cloud → Cloud Vision API → Credentials |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | للشراء | انظر القسم 6 |
| `PLAY_RTDN_SECRET` | للشراء | نص عشوائي طويل تختاره أنت |
| `FCM_SERVICE_ACCOUNT_JSON` | للإشعارات | انظر القسم 7 |

ثم Actions → Deploy backend → Run workflow (ويعمل تلقائياً بعد كل دمج في `main`).
الأسرار الداخلية (`GIFT_CODE_PEPPER` و`ADMIN_SECRET_PEPPER` و`WORKER_SECRET`) تُنشأ على الخادم مرة واحدة ولا تُغيَّر.
مفاتيح `service_role` وكلمات المرور لا تُرسل لأي شخص ولا توضع في التطبيق.

## 2. تسجيل الدخول

**البريد وكلمة المرور (الأساسي):** يعمل مباشرة. في Supabase: Authentication ← Sign In / Providers ← Email،
أوقف **Confirm email** حتى لا يحتاج المستخدم رسالة تأكيد (بريد Supabase المجاني محدود جداً بعدد الرسائل في الساعة).
فعّله لاحقاً بعد ضبط SMTP خاص بك (Authentication ← Emails ← SMTP Settings).

### Google (اختياري)

زر Google يظهر فقط إذا بُني التطبيق مع `GOOGLE_WEB_CLIENT_ID`.

1. في Google Cloud Console أنشئ OAuth Client من نوع **Web application**. هذا هو `GOOGLE_WEB_CLIENT_ID`.
2. أنشئ أيضاً OAuth Client من نوع **Android** باسم الحزمة `com.mangatranslator.app`
   (و`com.mangatranslator.app.debug` للنسخة التجريبية، وبصمتها `97:74:EE:CD:DA:68:71:1E:4C:EE:78:87:99:4D:60:F1:39:EF:35:B4`) وبصمة SHA-1 لمفتاح التوقيع.
3. في Supabase: Authentication → Providers → Google، فعّله وضع Web client ID والسر.
   (أو `GOOGLE_WEB_CLIENT_ID` و`GOOGLE_WEB_CLIENT_SECRET` في البيئة إذا استخدمت `config.toml`.)

## 3. معالجة الطابور (pg_cron)

`jobs-start` يوقظ العامل مباشرة، لكن يجب جدولة العامل كل دقيقة كاحتياط، ولإنهاء الاشتراكات المنتهية.
في SQL Editor (بعد تفعيل الإضافتين `pg_cron` و`pg_net`):
```sql
select cron.schedule('jobs-worker', '* * * * *', $$
  select net.http_post(
    url := 'https://<ref>.supabase.co/functions/v1/jobs-worker',
    headers := jsonb_build_object('x-worker-secret', '<WORKER_SECRET>', 'content-type', 'application/json'),
    body := '{}'::jsonb);
$$);
select cron.schedule('expire-subscriptions', '*/15 * * * *', $$ select public.expire_subscriptions(); $$);
```

## 4. أول مدير

```bash
ADMIN_SECRET_PEPPER=<نفس القيمة في Supabase> \
  deno run --allow-env backend/scripts/create-admin-secret.ts "owner" 1 7
```
يطبع الكود مرة واحدة وجملة SQL. نفّذ الجملة في SQL Editor، ثم في التطبيق:
الملف الشخصي ← اضغط على رقم الإصدار 7 مرات ← أدخل الكود.

## 5. تطبيق Android

المتطلبات: JDK 17 وAndroid SDK 35 (أو Android Studio حديث).

أنشئ `android/local.properties` (مُستثنى من Git):
```properties
sdk.dir=/path/to/Android/sdk
SUPABASE_URL=https://<ref>.supabase.co
SUPABASE_ANON_KEY=<anon key>
GOOGLE_WEB_CLIENT_ID=<web client id>.apps.googleusercontent.com
```
ثم:
```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```
بدون هذه القيم يُبنى التطبيق ويعرض شاشة "الخادم غير مضبوط" بدل أن يتعطل.

### التوقيع للإصدار

أنشئ keystore (`keytool -genkeypair -v -keystore release.jks -alias mt -keyalg RSA -keysize 4096 -validity 10000`)
ثم `android/keystore.properties` (مُستثنى من Git):
```properties
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=mt
keyPassword=...
```
`./gradlew bundleRelease` ينتج AAB لـ Google Play.

## 6. الشراء عبر Google Play

1. Play Console → أنشئ التطبيق باسم الحزمة `com.mangatranslator.app` وارفع أول AAB موقّع إلى مسار **Internal testing**
   (لا تظهر منتجات الاشتراك قبل رفع نسخة تحتوي مكتبة Billing).
2. Monetize → Subscriptions: أنشئ اشتراكين بمعرّفين مثل `pro_monthly` و`premium_monthly`، ولكل منهما base plan شهري تجدد تلقائياً بالسعر الذي تريده.
3. اربط المعرّفين بالخطط في SQL Editor:
   ```sql
   update public.subscription_plans set play_product_id = 'pro_monthly' where id = 'pro';
   update public.subscription_plans set play_product_id = 'premium_monthly' where id = 'premium';
   ```
4. Google Cloud → IAM → Service Accounts: أنشئ حساب خدمة وحمّل مفتاح JSON. ثم Play Console → Users and permissions →
   ادعُ بريد حساب الخدمة وامنحه "View financial data" و"Manage orders and subscriptions".
   ضع محتوى ملف JSON كاملاً في السر `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`.
5. الإشعارات الفورية (RTDN): في Google Cloud → Pub/Sub أنشئ topic، وامنح `google-play-developer-notifications@system.gserviceaccount.com` دور Publisher عليه،
   ثم اشتراكاً من نوع **Push** إلى:
   `https://<ref>.supabase.co/functions/v1/billing-rtdn?secret=<PLAY_RTDN_SECRET>`
   وفي Play Console → Monetization setup ضع اسم الـ topic.
6. أعد تشغيل Deploy backend. التطبيق يعرض الأسعار من Play مباشرة، والخادم يتحقق من كل عملية شراء ويمنح الخطة ويؤكدها.

بدون هذه الخطوات يبقى زر الشراء معطّلاً مع شرح، وأكواد الهدايا تعمل.

## 7. إشعارات الدفع (Firebase)

1. console.firebase.google.com → أضف مشروعاً (يمكن اختيار نفس مشروع Google Cloud) → أضف تطبيق Android باسم الحزمة `com.mangatranslator.app`.
2. من Project settings → General خذ القيم التالية وضعها أسراراً في GitHub (هي معرّفات عامة وليست أسراراً، لكن الأسرار أسهل للضبط من الهاتف):
   `FIREBASE_APP_ID` (mobilesdk_app_id)، و`FIREBASE_API_KEY` (current_key)، و`FIREBASE_PROJECT_ID`، و`FIREBASE_SENDER_ID` (project_number).
   وأضف `GOOGLE_WEB_CLIENT_ID` من القسم 2 بنفس الطريقة. بناء Android في GitHub يقرؤها تلقائياً.
3. Project settings → Service accounts → Generate new private key، وضع محتوى الملف في السر `FCM_SERVICE_ACCOUNT_JSON`.
4. أعد تشغيل Deploy backend. يصل إشعار عند انتهاء كل فصل، والضغط عليه يفتح الفصل.

## 8. الاختبارات

```bash
bash backend/scripts/test-db.sh         # يحتاج PostgreSQL 15+ محلياً أو DATABASE_URL
bash backend/scripts/test-functions.sh  # يحتاج Deno 2
cd android && ./gradlew testDebugUnitTest
```
نفس الاختبارات تعمل تلقائياً في GitHub Actions (`.github/workflows/`)، ويُرفع ملف APK التجريبي كـ artifact.
