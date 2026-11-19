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

## 2. تسجيل الدخول بـ Google

1. في Google Cloud Console أنشئ OAuth Client من نوع **Web application**. هذا هو `GOOGLE_WEB_CLIENT_ID`.
2. أنشئ أيضاً OAuth Client من نوع **Android** باسم الحزمة `com.mangatranslator.app`
   (و`com.mangatranslator.app.debug` للنسخة التجريبية) وبصمة SHA-1 لمفتاح التوقيع.
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

## 6. الاختبارات

```bash
bash backend/scripts/test-db.sh         # يحتاج PostgreSQL 15+ محلياً أو DATABASE_URL
bash backend/scripts/test-functions.sh  # يحتاج Deno 2
cd android && ./gradlew testDebugUnitTest
```
نفس الاختبارات تعمل تلقائياً في GitHub Actions (`.github/workflows/`)، ويُرفع ملف APK التجريبي كـ artifact.
