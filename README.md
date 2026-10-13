# ScreenTranslator2 — Android-компонент

Android-приложение системы Translate via Phone. Выполняет приём видеопотока
от ПК, распознавание текста средствами ML Kit, стабилизацию блоков между
кадрами, отправку OCR-результатов на ПК и приём перевода. Поддерживает
локальный оверлей зон и режим перевода.

Связанный компонент: ПК-приложение translate_via_phone (см. отдельный README).

---

## Назначение

Android-компонент отвечает за распознавание текста в видеопотоке,
полученном от ПК, и за взаимодействие с ПК по протоколу HTTP.
OCR выполняется на устройстве средствами ML Kit, что обеспечивает
высокое качество распознавания UI-текста без нагрузки на ПК.

Основные сценарии:

- Режим PcOcrService: приём MJPEG-потока от ПК, OCR, отправка блоков
  на ПК, приём перевода, отображение.
- Режим OverlayService: локальный захват экрана устройства,
  OCR, отображение зон и перевода поверх экрана.

---

## Функциональные возможности

Приём видеопотока:

- MJPEG-клиент с автоматическим переподключением (backoff 1–3 с).
- Обработка обрывов соединения без остановки сервиса.
- Логирование переподключений в DebugTimingLogger.

OCR:

- ML Kit Text Recognition (single-pass, без refine).
- Извлечение bounding box и confidence для каждого блока.
- Нормализация текста (OcrTextNormalizer).
- Фильтрация нерелевантных блоков (isMeaningfulText).
- Деление compound-блоков по тексту и цифрам (splitByTextAndNumbers).

Стабилизация:

- OcrBlockStabilizer: удержание блоков между кадрами.
- Матчинг по IoU, center distance, containment.
- Порог пропущенных кадров (STABLE_MISS_LIMIT).
- Геометрическое сглаживание (POS_CHANGE_THRESHOLD,
  SIZE_CHANGE_THRESHOLD, BBOX_SETTLE_FRAMES).

Перевод:

- BenchmarkTranslator: HTTP (CTranslate2 на ПК) + ML Kit (fallback).
- Приоритет HTTP при доступности сервера.
- Автоматический переход на ML Kit при недоступности HTTP.
- Кэш переводов с TTL (60 секунд).
- Отправка текстов на POST /translate.

Передача данных:

- OcrSender: асинхронная отправка OCR-блоков на ПК (POST /ocr).
- Формат JSON: frame_width, frame_height, mode, overlay_alpha, blocks.

Настройки:

- SharedPreferences (Settings.kt).
- Приём настроек от ПК через BroadcastReceiver (SettingsReceiver).
- Отправка настроек на ПК через HTTP (SettingsActivity).
- Синхронизация односторонняя: ПК → Android через broadcast,
  Android → ПК через HTTP.

Сервисы:

- PcOcrService — основной сервис (приём потока, OCR, отправка).
- OverlayService — локальный оверлей (MediaProjection, ZoneOverlayView).

GUI:

- MainActivity — главный экран (кнопка СТАРТ/СТОП, переход в настройки).
- SettingsActivity — настройки (debug, confidence, ocr_padding,
  overlay_alpha, source_lang, target_lang, translate_profile,
  загрузка моделей ML Kit).
- ZoneOverlayView — отрисовка зон OCR и перевода.

Отладка:

- DebugTimingLogger: агрегация таймингов, отправка на ПК (POST /debug).

---

## Архитектура

```
┌──────────────────── Android (ScreenTranslator2) ────────────────┐
│                                                                  │
│  MainActivity                                                    │
│    └─ запуск/остановка PcOcrService                              │
│                                                                  │
│  PcOcrService                                                    │
│    ├─ MJPEGReader (HTTP :5005/stream)                            │
│    ├─ MlKitOcrEngine (ML Kit Text Recognition)                   │
│    ├─ OcrTextNormalizer (нормализация, фильтрация)               │
│    ├─ OcrBlockStabilizer (стабилизация блоков)                   │
│    ├─ BenchmarkTranslator (HTTP :5008 + ML Kit fallback)         │
│    └─ OcrSender (HTTP POST :5006/ocr)                            │
│                                                                  │
│  SettingsActivity                                                │
│    ├─ SharedPreferences (Settings.kt)                            │
│    ├─ HTTP POST :5009/settings (отправка на ПК)                  │
│    └─ ML Kit RemoteModelManager (загрузка моделей)               │
│                                                                  │
│  SettingsReceiver (BroadcastReceiver)                            │
│    └─ приём настроек от ПК (ADB broadcast)                       │
│                                                                  │
│  DebugTimingLogger                                               │
│    └─ HTTP POST :5007/debug (отправка метрик на ПК)              │
└──────────────────────────────────────────────────────────────────┘
▲
│ ADB reverse tcp:5005..5009
▼
┌────────────────────────── ПК (Python) ──────────────────────────┐
│  WindowStreamer, OcrReceiver, TranslateServer, SettingsServer,   │
│  DebugReceiver, OcrOverlayWindow                                 │
└──────────────────────────────────────────────────────────────────┘
```

### Последовательность обработки кадра

1. MJPEGReader принимает кадр от ПК (HTTP :5005/stream).
2. MlKitOcrEngine выполняет OCR (single-pass).
3. OcrTextNormalizer нормализует текст и фильтрует нерелевантные блоки.
4. OcrBlockStabilizer стабилизирует блоки между кадрами.
5. В режиме debug: OcrSender отправляет блоки на ПК (POST /ocr).
6. В режиме translate: BenchmarkTranslator отправляет тексты
   на POST /translate, получает перевод, OcrSender отправляет
   переведённые блоки на ПК.

---

## Технологический стек

Язык: Kotlin

Платформа: Android (minSdk определяется проектом)

OCR: ML Kit Text Recognition (com.google.mlkit:vision-text)

Перевод: ML Kit Translate (fallback), HTTP CTranslate2 (основной)

HTTP-клиент: OkHttp 4 (OkHttpClient, ConnectionPool)

Асинхронность: Kotlin Coroutines, Channel, SupervisorJob

JSON: org.json (JSONObject, JSONArray)

Хранение настроек: SharedPreferences

Сервисы: Android Service, Foreground Service

Захват экрана: MediaProjection, VirtualDisplay, ImageReader

Оверлей: WindowManager, TYPE_APPLICATION_OVERLAY, ZoneOverlayView

Сборка: Gradle (Android Studio)

---

## Структура проекта

```
app/src/main/java/com/example/screentranslator/
├── MainActivity.kt                # главный экран
├── SettingsActivity.kt            # экран настроек
├── Settings.kt                    # SharedPreferences, константы
├── SettingsReceiver.kt            # приём настроек от ПК (broadcast)
├── PcOcrService.kt                # основной сервис (OCR ПК)
├── OverlayService.kt              # сервис локального оверлея
├── MJPEGReader.kt                 # MJPEG-клиент
├── OcrSender.kt                   # отправка OCR на ПК
├── OcrEngine.kt                   # интерфейс, OcrResult, OcrBlock
├── OcrEngineFactory.kt            # фабрика движков
├── OcrTextNormalizer.kt           # нормализация и фильтрация текста
├── OcrBlockStabilizer.kt          # стабилизация блоков
├── OcrTranslator.kt               # ML Kit перевод (fallback)
├── BenchmarkTranslator.kt         # HTTP + ML Kit fallback
├── DebugTimingLogger.kt           # debug-метрики (отправка на ПК)
├── ScreenCaptureHelper.kt         # MediaProjection capture
├── ZoneOverlayView.kt             # отрисовка зон и перевода
└── engines/
└── MlKitOcrEngine.kt          # ML Kit OCR (single-pass)
```

---

## Порты и endpoints

| Порт | Назначение                              |
|------|-----------------------------------------|
| 5005 | MJPEG-поток от ПК                       |
| 5006 | Отправка OCR-результатов на ПК          |
| 5007 | Отправка debug-метрик на ПК             |
| 5008 | HTTP-сервер перевода (CTranslate2)      |
| 5009 | HTTP-сервер настроек                    |

Все порты доступны через adb reverse (localhost на устройстве).

---

## Конфигурация (SharedPreferences)

Ключи (Settings.kt):

- debug — режим отображения OCR-рамок.
- confidence — порог уверенности OCR (0–100).
- source_lang, target_lang — языки.
- overlay_alpha — прозрачность фона перевода (0–100).
- ocr_padding — отступ OCR (0–100).
- translate_profile — speed, balanced, quality.
- downloaded_models — множество скачанных моделей ML Kit.
- debug_timing_enabled, debug_verbose, debug_endpoint,
  debug_aggregate_window_ms — параметры debug-логгера.
- translate_endpoint — endpoint HTTP-перевода.
- use_server_translation — использовать HTTP-перевод.
- auto_launch_android — автозапуск приложения с ПК.

Синхронизация: ПК → Android через ADB broadcast (SettingsReceiver),
Android → ПК через HTTP POST /settings (SettingsActivity).

---

## Разрешения

- INTERNET — HTTP-запросы к ПК.
- FOREGROUND_SERVICE — PcOcrService, OverlayService.
- POST_NOTIFICATIONS (Android 13+) — уведомления сервисов.
- SYSTEM_ALERT_WINDOW — локальный оверлей (OverlayService).
- Запрос MediaProjection — захват экрана (OverlayService).

---

## Ограничения

- Требуется ADB reverse, настроенный на стороне ПК.
- ML Kit Translate требует загрузки языковых моделей (по Wi-Fi).
- При отсутствии HTTP-сервера перевода используется ML Kit
  (качество ниже, чем у CTranslate2).
- Локальный оверлей (OverlayService) требует разрешения
  на наложение поверх других окон.

---

## Реализованные технические решения

- Однопроходный ML Kit OCR без refine: bounding box берётся
  из первого прохода, без пересчёта по crop-зонам, что устраняет
  обрезку многострочных блоков.
- Стабилизация блоков между кадрами: удержание при частичном
  совпадении текста (containment), обновление текста только
  после N последовательных совпадений (STABLE_TEXT_UPDATE_THRESHOLD),
  сглаживание геометрии (BBOX_SETTLE_FRAMES, GEO_UPDATE_THRESHOLD,
  GEO_DEAD_ZONE).
- Снижение порога пропущенных кадров (STABLE_MISS_LIMIT) для
  устранения залипания устаревших блоков при смене UI.
- При containment-матче с разным текстом lastSeenFrame не
  продлевается — старый блок удаляется через pruneStableBlocks,
  новый добавляется отдельно.
- Нормализация текста: удаление числовых хвостов, ведущих
  префиксов, редких символов, скоплений согласных.
- Фильтрация мусора: isMeaningfulText (смешанные алфавиты,
  регистр, цифры между буквами, отсутствие гласных,
  биграммный анализ для латиницы).
- Деление compound-блоков по тексту и цифрам
  (splitByTextAndNumbers) с сохранением bounding box.
- Двухдвижковый перевод: HTTP CTranslate2 (основной) и ML Kit
  Translate (fallback) с автоматическим переключением при
  недоступности сервера.
- Кэш переводов с TTL и ограничением размера, очистка
  устаревших записей.
- Асинхронная отправка OCR на ПК через OkHttp с Callback,
  подсчёт успешных и неуспешных запросов.
- Очередь translationQueue (Channel, capacity 4) для обработки
  OCR-job в одном воркере, дедупликация по хэшу блоков и режиму.
- Drain очереди при смене debug-режима для устранения мерцания
  между translate и debug.
- Односторонняя синхронизация настроек: ПК → Android через
  ADB broadcast, Android → ПК через HTTP POST.
- DebugTimingLogger с агрегацией таймингов (avg/min/max/p95)
  и отправкой батчами на ПК (POST /debug).
- Локальный оверлей через MediaProjection + ZoneOverlayView
  с подбором размера шрифта и StaticLayout.
- Отрисовка debug-зон двумя рамками: красная (bounding box)
  и зелёная (bounding box с учётом ocr_padding).

---

## Зависимости между компонентами

- ADB reverse на стороне ПК требуется для доступа к портам
  5005–5009 через localhost на устройстве.
- HTTP-сервер перевода на ПК (CTranslate2) требуется для
  основного режима перевода. При его отсутствии используется
  ML Kit Translate.
- HTTP-сервер настроек на ПК требуется для отправки изменений
  настроек с Android.
- ПК-компонент требуется для передачи MJPEG-потока и приёма
  OCR-результатов в режиме PcOcrService.