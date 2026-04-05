# Diploma Verification Backend (MVP)

Backend на `Ktor + PostgreSQL + Redis` с ролями: `super-admin`, `university`, `hr`, `student`.

## Быстрый старт (рекомендуется для новых пользователей)

### 1. Требования

- JDK 21
- Docker Desktop (запущен)

### 2. Перейти в папку backend

Важно запускать Gradle именно из директории проекта:

```powershell
cd D:\umirhak\umirhak2026_backend
```

Если запускать из родительской папки, будет ошибка:
`.\gradlew.bat: The term '.\gradlew.bat' is not recognized...`

### 3. Поднять PostgreSQL и Redis

```powershell
docker compose up -d postgres redis email-service
```

В этом проекте PostgreSQL проброшен на порт `55432` (чтобы избежать конфликтов с локальным Postgres на `5432`).

### 4. Указать переменные окружения для локального запуска

```powershell
$env:JDBC_URL="jdbc:postgresql://localhost:55432/diasoft"
$env:DB_USER="diasoft"
$env:DB_PASSWORD="diasoft"
```

### 5. Запустить сервер

```powershell
.\gradlew.bat run
```

### 6. Проверить, что сервер жив

```powershell
iwr http://127.0.0.1:8080/health
```

Ожидаемый ответ: `{"status":"ok"}`.

## Альтернатива: запуск всего через Docker

```powershell
docker compose up --build
```

## SMTP микросервис (сброс пароля)

Отдельный сервис писем расположен в папке `D:\umirhak\umirhak2026_backend\email_service`.

1. Скопируйте шаблон переменных:

```powershell
Copy-Item .env.example .env
```

2. Заполните в `.env` SMTP-параметры Яндекс и пароль приложения.
3. Поднимите сервисы:

```powershell
docker compose up -d --build email-service postgres redis
```

Проверка email-сервиса:

```powershell
iwr http://127.0.0.1:8090/health
```

## Основные переменные конфигурации

- `JDBC_URL`, `DB_USER`, `DB_PASSWORD`
- `REDIS_URL`
- `SUPERADMIN_LOGIN`, `SUPERADMIN_PASSWORD`
- `HASH_SALT`, `ENCRYPTION_KEY_BASE64`
- `PUBLIC_BASE_URL`

Значения по умолчанию описаны в `src/main/resources/application.yaml`.

## Частые проблемы и решения

### 1) `gradlew.bat` не найден

Причина: команда выполнена не из папки `umirhak2026_backend`.

Решение:
```powershell
cd D:\umirhak\umirhak2026_backend
.\gradlew.bat run
```

### 2) Таймаут при скачивании Gradle wrapper

Симптом:
`Downloading ... failed: timeout`

Проверьте интернет/прокси/VPN и повторите:
```powershell
.\gradlew.bat run
```

### 3) `user "diasoft" password authentication failed`

Причины:
- конфликт порта `5432` с другим Postgres на хосте
- приложение подключается не к тому экземпляру базы

Решение для этого проекта:
1. Использовать `JDBC_URL` с портом `55432`
2. Запустить контейнеры из `docker-compose.yml`
3. Указать:
```powershell
$env:JDBC_URL="jdbc:postgresql://localhost:55432/diasoft"
$env:DB_USER="diasoft"
$env:DB_PASSWORD="diasoft"
```

### 4) Нужно полностью пересоздать БД

Если состояние БД сломано или credentials не совпадают:

```powershell
docker compose down -v
docker compose up -d postgres redis
```

`-v` удаляет volume с данными PostgreSQL.

### 5) `app` контейнер в статусе `exited` и `:8080` не отвечает

1. Посмотреть причину падения:
```powershell
docker compose ps -a
docker compose logs --tail=200 app
```
2. Если ошибка связана с Flyway/миграциями (checksum, validate failed, relation does not exist), сбросьте volume БД:
```powershell
docker compose down -v
docker compose up -d --build
```
3. Если в логах есть `bind: address already in use` для `0.0.0.0:8080`, порт занят другим процессом.
Либо освободите порт, либо запустите backend на другом порту:
```powershell
$env:APP_PORT="18080"
docker compose up -d --build
```
4. Проверить health:
```powershell
iwr http://127.0.0.1:8080/health
# если APP_PORT=18080:
iwr http://127.0.0.1:18080/health
```

## Полезные endpoints

- Health: `GET /health`
- API base: `/api/v1/...`

## Короткий smoke-тест после запуска

1. `GET http://127.0.0.1:8080/health` -> `{"status":"ok"}`
2. `POST /api/v1/hr/register` (любой тестовый пользователь)
3. Убедиться, что ответ `200` и сервис не падает
