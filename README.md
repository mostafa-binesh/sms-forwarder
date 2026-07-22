# 📱 SmsForwarder

**انتقال خودکار SMS بانکی به تلگرام**

اپلیکیشن اندرویدی که SMS های بانکی شما رو به صورت خودکار به تلگرام فوروارد می‌کنه تا توسط Hermes (AI) پردازش و دسته‌بندی بشه.

## 🏗️ معماری

```
📱 گوشی (Android)
  → BroadcastReceiver (SMS جدید)
  → BankFilter (فیلتر SMS بانکی)
  → TelegramSender (HTTP POST به Bot API)
      ↓
🤖 Telegram Bot
      ↓
🧠 Hermes (پردازش + دسته‌بندی + گزارش)
```

## ⚡ شروع سریع

### ۱. Telegram Bot بسازید

1. در تلگرام `@BotFather` رو باز کنید
2. `/newbot` رو بزنید
3. یه اسم بذارید (مثلاً `MySmsForwarder`)
4. یه یوزرنیم بذارید (مثلاً `mostafa_sms_bot`)
5. **توکن** رو کپی کنید

### ۲. Chat ID پیدا کنید

1. بات رو استارت کنید
2. یه پیام بفرستید
3. بروید به: `https://api.telegram.org/bot<TOKEN>/getUpdates`
4. شناسه چت (`chat.id`) رو پیدا کنید

### ۳. اپ رو بیلد کنید

```bash
# پروژه رو کلون/باز کنید
cd sms-forwarder

# بیلد APK
./gradlew assembleDebug

# APK در مسیر زیر هست:
# app/build/outputs/apk/debug/app-debug.apk
```

### ۴. نصب و تنظیم

1. APK رو روی گوشی نصب کنید
2. اجازه خواندن SMS رو بدید
3. توکن بات و Chat ID رو وارد کنید
4. حالت فیلتر رو انتخاب کنید
5. فعال‌سازی رو روشن کنید

## ⚙️ تنظیمات

### حالت فیلتر

| حالت | توضیح |
|---|---|
| **لیست سفید** | فقط SMS از شماره‌های مشخص‌شده |
| **خودکار** | تشخیص خودکار SMS بانکی (پیش‌فرض) |
| **همه** | تمام SMS ها فوروارد بشه |

### کلمات کلیدی پیش‌فرض (حالت خودکار)

```
واریز, برداشت, مانده, حساب, انتقال, موجودی, خرید
```

می‌تونید کلمه اضافه/حذف کنید.

## 📁 ساختار پروژه

```
app/src/main/java/com/mostafa/smsforwarder/
├── SmsForwarderApp.kt          # Application class
├── receiver/
│   ├── SmsReceiver.kt          # دریافت SMS
│   └── BootReceiver.kt         # شروع بعد از ریستارت
├── filter/
│   └── BankFilter.kt           # فیلتر SMS بانکی
├── sender/
│   └── TelegramSender.kt       # ارسال به تلگرام
├── db/
│   ├── SmsLog.kt               # Room Entity
│   ├── SmsLogDao.kt            # Room DAO
│   └── AppDatabase.kt          # Room Database
├── util/
│   ├── SettingsManager.kt      # مدیریت تنظیمات
│   └── SmsParser.kt            # پارسر SMS بانکی
└── ui/
    └── MainActivity.kt         # صفحه تنظیمات
```

## 🔒 امنیت

- ✅ فقط SMS بانکی فوروارد میشه
- ✅ توکن بات فقط در گوشی ذخیره میشه
- ✅ لاگ SMS در SQLite محلی ذخیره میشه
- ✅ هیچ اطلاعاتی به سرور ثالث ارسال نمیشه

## 📋 نیازمندی‌ها

- اندروید ۸.۰ (API 26) یا بالاتر
- اجازه خواندن SMS
- اینترنت (برای ارسال به تلگرام)

## 🛠️ تکنولوژی‌ها

- **زبان:** Kotlin
- **بیلد:** Gradle 8.5 + AGP 8.2.0
- **ORM:** Room Database
- **HTTP:** OkHttp3
- **UI:** Material Design 3
- ** coroutine:** Kotlin Coroutines
