# TSC QuickPrint — Android TSC TE200 Print

**English** · [Русский](#русский)

Android app and **system print plugin** for the **TSC TE200** thermal label printer
(TSPL2, 203 dpi) connected to a phone via **USB-C OTG**. Print labels (including
multi-page PDFs with barcodes and DataMatrix) straight from your phone — no laptop needed.

## Features

- **System Print Service**: the `TSC TE200` printer appears in the standard Android
  print dialog (“Share → Print”).
- **Direct printing from the app**: pick a PDF → preview → print.
- **“Share → TSC QuickPrint”**: one-page PDFs print immediately; multi-page PDFs open
  in the preview so you can choose pages before printing.
- **Multi-page PDFs**: every page is printed as a separate label.
- **Page preview and selection**: horizontal page previews, checkboxes, all pages
  selected by default, manual selection, and page ranges.
- **PDF rendering**: vector, raster and mixed pages are rasterized with `PdfRenderer`.
- **Fit to label**: “Fit entirely” (contain) or “Fill with crop” (cover), keeping aspect ratio.
- **Binarization**: hard threshold (barcodes) or Floyd–Steinberg dithering (photos).
- **Trim white margins** around the image.
- **Settings**: label size, gap, print density, threshold, scaling mode.
- **Sensor calibration**: send `GAPDETECT` to the TE200 to calibrate the label gap sensor.
- **Bilingual UI**: English / Русский, switchable in Settings.
- **State preservation**: the selected PDF and page selection survive language changes;
  changing language does not start a new print job.
- **Material 3 (Material You)**: dynamic colors on Android 12+, light/dark theme.

## Requirements

- Phone with **USB Host (OTG)**, Android 8.0+ (API 26+).
- **USB-C OTG adapter** + **USB A–B cable**.
- **TSC TE200** printer (203 dpi, TSPL2), self-powered.
- To build: JDK 17, Android SDK 35, Gradle (wrapper included).

## Install

Ready APK: [`apk/TSC-QuickPrint-debug.apk`](apk/TSC-QuickPrint-debug.apk)

```bash
adb install -r apk/TSC-QuickPrint-debug.apk
```

Or copy the APK to the phone and install manually (allowing unknown sources).

## Build

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Connect the TE200 to the phone via **USB-C OTG**.
2. Open **TSC QuickPrint**:
   - set the label size (e.g. 58×30 mm), gap and density;
   - tap **“Allow USB”** and confirm access;
   - optionally tap **“Test print”** to verify.
3. Print:
   - **from the app**: “Choose PDF file” → preview → “Print”;
   - **from the system**: in any app “Share → Print → TSC TE200”;
   - **via sharing**: “Share → TSC QuickPrint”; single-page PDFs print immediately,
     while multi-page PDFs show the page preview first;
   - **sensor calibration**: in the “Printer access” section, tap “Calibrate label gap
     sensor” after loading labels. The printer will feed media while measuring the gap.
4. **Language**: tap the gear icon (top-right) → Settings → choose English / Русский.

## How it works

- The PDF is rasterized by `PdfRenderer` at 203 dpi (8 dots/mm), fitted to the label,
  binarized and packed into a 1-bit bitmap.
- **TSPL2** commands are generated: `SIZE`, `GAP`, `DENSITY`, `DIRECTION`, `REFERENCE`,
  `CLS`, `BITMAP`, `PRINT`.
- Data is sent to the printer's USB bulk endpoint (`UsbManager` → `bulkTransfer`).
- The print service receives the document from the system spooler and processes it in the
  background, while `PrintJob` calls run on the main thread (Android requirement).

## Limitations

- Only printers that understand **TSPL/TSPL2** (primarily TSC). Zebra (ZPL), ESC/POS
  receipts, Godex (EZPL) are not supported.
- Tuned for **203 dpi**; 300-dpi models would need different dot math.
- **USB only**; Bluetooth/network are not implemented.
- `BITMAP` polarity is set for the TE200 (may be inverted on other firmware).

## Project layout

```
app/src/main/java/com/example/tscprint/
  MainActivity.kt     — UI (Material 3), PDF pick, preview, USB, sharing, language
  TscPrintService.kt  — system print service plugin
  PdfToTspl.kt        — PDF → raster → binarize → TSPL
  PagePreviewAdapter.kt — horizontal multi-page preview and page checkboxes
  PageSelection.kt    — page ranges and selected-page state
  UsbPrinter.kt       — USB printer discovery, permission, bulk transfer
  PrintSettings.kt    — settings (SharedPreferences)
  LocaleHelper.kt     — per-app language (English / Russian)
  App.kt              — applies Material You dynamic colors
```

## License

Provided as is, without warranty. Add a license if needed.

---

<a id="русский"></a>
# TSC QuickPrint — печать на TSC TE200 (Android)

[English](#tsc-quickprint--android-tsc-te200-print) · **Русский**

Android‑приложение и **плагин системной печати** для термопринтера **TSC TE200**
(TSPL2, 203 dpi), подключаемого к смартфону по **USB‑C OTG**. Печатайте этикетки
(включая многостраничные PDF со штрих‑кодами и DataMatrix) прямо с телефона — без ноутбука.

## Возможности

- **Системная служба печати**: принтер `TSC TE200` появляется в стандартном диалоге
  Android «Поделиться → Печать».
- **Прямая печать из приложения**: выбор PDF → предпросмотр → печать.
- **«Поделиться → TSC QuickPrint»**: отправка PDF из любого приложения и печать сразу.
- **Многостраничные PDF**: каждая страница печатается как отдельная этикетка.
- **Рендеринг PDF**: вектор, растр и смешанные страницы растеризуются через `PdfRenderer`.
- **Масштабирование под этикетку**: «Вписать целиком» (contain) или «Заполнить с обрезкой»
  (cover) с сохранением пропорций.
- **Бинаризация**: жёсткий порог (штрих‑коды) или дизеринг Флойда–Стейнберга (фото).
- **Подрезка белых полей** вокруг изображения.
- **Настройки**: размер этикетки, зазор, плотность, порог, режим масштабирования.
- **Предпросмотр и выбор страниц**: горизонтальная лента страниц, галочки, выбор всех
  страниц по умолчанию, ручной выбор и диапазоны страниц.
- **Двуязычный интерфейс**: English / Русский, переключение в настройках.
- **Калибровка датчика**: команда `GAPDETECT` для автоматического определения длины
  этикетки и зазора.
- **Сохранение состояния**: выбранный PDF и страницы сохраняются при смене языка;
  повторная печать при этом не запускается.
- **Material 3 (Material You)**: динамические цвета на Android 12+, тёмная/светлая тема.

## Требования

- Смартфон с **USB Host (OTG)**, Android 8.0+ (API 26+).
- **USB‑C OTG адаптер** + кабель **USB A–B**.
- Принтер **TSC TE200** (203 dpi, TSPL2), с отдельным питанием.
- Для сборки: JDK 17, Android SDK 35, Gradle (wrapper в комплекте).

## Установка

Готовый APK: [`apk/TSC-QuickPrint-debug.apk`](apk/TSC-QuickPrint-debug.apk)

```bash
adb install -r apk/TSC-QuickPrint-debug.apk
```

Или скопируйте APK на телефон и установите вручную (разрешив неизвестные источники).

## Сборка

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Использование

1. Подключите TE200 к телефону через **USB‑C OTG**.
2. Откройте **TSC QuickPrint**:
   - задайте размер этикетки (например, 58×30 мм), зазор и плотность;
   - нажмите **«Разрешить USB»** и подтвердите доступ;
   - при желании нажмите **«Тестовая печать»**.
3. Печать:
   - **из приложения**: «Выбрать PDF‑файл» → предпросмотр → «Напечатать»;
   - **через систему**: в любом приложении «Поделиться → Печать → TSC TE200»;
   - **через шаринг**: «Поделиться → TSC QuickPrint». Одностраничный PDF печатается
     сразу, многостраничный сначала открывается в предпросмотре;
   - **калибровка**: в разделе «Доступ к принтеру» нажмите «Калибровка по датчику
     зазора». Принтер протянет материал и измерит зазор.
4. **Язык**: нажмите шестерёнку справа вверху → Настройки → English / Русский.

## Как это работает

- PDF растеризуется `PdfRenderer` в разрешении 203 dpi (8 точек/мм), подгоняется под размер
  этикетки, бинаризуется и упаковывается в 1‑битный растр.
- Формируются команды **TSPL2**: `SIZE`, `GAP`, `DENSITY`, `DIRECTION`, `REFERENCE`, `CLS`,
  `BITMAP`, `PRINT`.
- Данные отправляются в USB bulk‑endpoint принтера (`UsbManager` → `bulkTransfer`).
- Служба печати получает документ из системного спулера и обрабатывает его в фоне,
  а вызовы `PrintJob` выполняются в главном потоке (требование Android).

## Ограничения

- Только принтеры, понимающие **TSPL/TSPL2** (в первую очередь TSC). Zebra (ZPL),
  ESC/POS‑чеки, Godex (EZPL) — не поддерживаются.
- Заточено под **203 dpi**; для 300‑dpi моделей размер в точках будет иным.
- Только **USB**; Bluetooth/сеть не реализованы.
- Полярность `BITMAP` настроена под TE200 (в некоторых прошивках может быть обратной).

## Структура проекта

```
app/src/main/java/com/example/tscprint/
  MainActivity.kt     — UI (Material 3), выбор PDF, предпросмотр, USB, шаринг, язык
  TscPrintService.kt  — системная служба печати (Print Service plugin)
  PdfToTspl.kt        — PDF → растр → бинаризация → TSPL
  PagePreviewAdapter.kt — горизонтальный предпросмотр и галочки страниц
  PageSelection.kt    — диапазоны и состояние выбранных страниц
  UsbPrinter.kt       — поиск USB-принтера, разрешение, bulk-передача
  PrintSettings.kt    — настройки (SharedPreferences)
  LocaleHelper.kt     — язык приложения (English / Russian)
  App.kt              — применение динамических цветов Material You
```

## Лицензия

Проект распространяется как есть, без гарантий. Добавьте лицензию при необходимости.
