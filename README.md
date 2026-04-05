# UMIRHAK 2026 Backend

Серверная часть проекта «Честный Диплом» - системы реестра дипломов с проверкой подлинности, ролями и безопасным доступом к данным.

## Что это за проект

«Честный Диплом» - это платформа, которая решает проблему долгой и непрозрачной проверки дипломов.

Задача проекта:

- обеспечить единый реестр дипломов;
- дать ВУЗам удобную публикацию и поддержку данных;
- позволить выпускникам безопасно делиться подтверждением;
- дать работодателям быстрый способ проверки подлинности;
- снизить риск поддельных дипломов и ошибок при найме.

Бэкенд реализует API, авторизацию по ролям, хранение данных, интеграцию с Redis и почтовым сервисом для восстановления доступа.

## Роли и демо-аккаунты

Роли в системе:

- university (представитель ВУЗа)
- student (студент)
- employer/hr (работодатель)
- admin (администратор платформы)
- superadmin (суперпользователь)

Демо-аккаунты фронтенда (для входа в ролевые кабинеты):

- Представитель ВУЗа:
	- login: vuz@demo.diasoft
	- password: VuzDemo2026
- Студент:
	- login: student@demo.diasoft
	- password: Student2026
- Работодатель (HR):
	- login: hr@demo.diasoft
	- password: HrDemo2026
- Администратор платформы:
	- login: admin@demo.diasoft
	- password: AdminDemo2026
- Суперпользователь:
	- login: super@demo.diasoft
	- password: SuperDemo2026

Важно:

- В backend также есть системные переменные `SUPERADMIN_LOGIN` и `SUPERADMIN_PASSWORD` (по умолчанию `admin/admin123`) для базовой серверной конфигурации.
- Если нужны единые креды между frontend demo и backend auth, задайте их согласованно в конфигурации окружения.

## Участники команды

- Зуев Александр - Frontend
- Межмал Алексей - Frontend
- Михайленко Максим - Backend

## Технологии

- Kotlin
- Ktor
- PostgreSQL
- Redis
- Flyway
- HikariCP
- Gradle
- Docker / Docker Compose
- Node.js email-service (вспомогательный микросервис)

## Как запустить проект

### Зависимости

- JDK 21
- Docker Desktop
- (опционально) Node.js, если нужно отдельно развивать email-service

### Вариант 1. Локальный запуск backend + контейнерные зависимости

1. Перейдите в директорию backend:

```powershell
cd D:\umirhak\umirhak2026_backend
```

2. Поднимите инфраструктурные сервисы:

```powershell
docker compose up -d postgres redis email-service
```

3. Задайте переменные окружения для приложения:

```powershell
$env:JDBC_URL="jdbc:postgresql://localhost:55432/diasoft"
$env:DB_USER="diasoft"
$env:DB_PASSWORD="diasoft"
$env:REDIS_URL="redis://localhost:6379"
```

4. Запустите сервер:

```powershell
.\gradlew.bat run
```

5. Проверьте health endpoint:

```powershell
iwr http://127.0.0.1:8080/health
```

Ожидаемый ответ: `{\"status\":\"ok\"}`.

### Вариант 2. Полностью через Docker Compose

```powershell
cd D:\umirhak\umirhak2026_backend
docker compose up --build
```

Этот режим поднимет:

- app (backend)
- postgres
- redis
- email-service

## Пример запуска связки frontend + backend

1. Поднимите backend (одним из способов выше).
2. В frontend задайте:

```bash
VITE_API_BASE_URL=http://localhost:8080
```

3. Запустите frontend (`npm run dev` в соседнем репозитории).

## Основные команды

```powershell
# запуск приложения
.\gradlew.bat run

# тесты
.\gradlew.bat test

# сборка
.\gradlew.bat build

# миграции Flyway (при необходимости)
.\gradlew.bat flywayMigrate
```

## Конфигурация

Ключевые переменные:

- `JDBC_URL`, `DB_USER`, `DB_PASSWORD`
- `REDIS_URL`
- `EMAIL_SERVICE_URL`
- `INTERNAL_SERVICE_TOKEN`
- `FRONTEND_BASE_URL`
- `PASSWORD_RESET_TTL_MINUTES`
- `PUBLIC_BASE_URL`
- `HASH_SALT`
- `ENCRYPTION_KEY_BASE64`
- `SUPERADMIN_LOGIN`, `SUPERADMIN_PASSWORD`
- `RATE_LIMIT_PER_MINUTE`
- `MAX_TTL_MINUTES`

Значения по умолчанию указаны в `src/main/resources/application.yaml` и `docker-compose.yml`.

## Структура проекта

Кратко по основным директориям:

- src/main/kotlin/com/example/config - конфигурация приложения.
- src/main/kotlin/com/example/routes - HTTP маршруты и API endpoints.
- src/main/kotlin/com/example/service - бизнес-логика.
- src/main/kotlin/com/example/security - безопасность, токены, ограничения.
- src/main/kotlin/com/example/db - доступ к данным и слой работы с БД.
- src/main/kotlin/com/example/model - доменные модели и DTO.
- src/main/kotlin/com/example/integration - интеграции с внешними сервисами.
- src/main/resources/db/migration - SQL миграции Flyway.
- email_service - Node.js микросервис отправки писем.

## Как пользоваться решением

### Сценарий 1. Регистрация/вход пользователя

1. Клиент отправляет запрос на auth endpoint.
2. Backend валидирует данные и роль.
3. Пользователь получает доступ к API своей роли.

### Сценарий 2. Верификация диплома работодателем

1. HR отправляет запрос проверки (по номеру/идентификатору/токену).
2. Backend проверяет запись в реестре и статус.
3. Возвращается результат верификации.

### Сценарий 3. Восстановление доступа

1. Пользователь инициирует reset.
2. Backend генерирует токен и отправляет письмо через email-service.
3. Пользователь устанавливает новый пароль в пределах TTL.

## Примеры API команд

```bash
# health
curl http://localhost:8080/health

# пример (маршрут зависит от текущей версии API)
curl -X POST http://localhost:8080/api/v1/hr/register \
	-H "Content-Type: application/json" \
	-d '{"login":"hr@example.com","password":"Secret123"}'
```

## Демо, деплой и материалы

- Локальный backend: http://localhost:8080
- Health-check: http://localhost:8080/health
- Email-service: http://localhost:8090/health
- Публичный деплой: пока не опубликован.
- Видео/скринкаст: пока не добавлен.

Рекомендуется добавить:

- ссылку на деплой API (если появится);
- короткий скринкаст потока "вход -> проверка диплома -> результат".

## Частые проблемы

### 1. Команда .\gradlew.bat не найдена

Запуск выполнен не из папки backend. Перейдите в `umirhak2026_backend` и повторите.

### 2. Ошибка подключения к PostgreSQL

Проверьте, что контейнер postgres запущен и используете порт `55432` для локального запуска JVM-приложения.

### 3. Порт уже занят

Освободите порт или измените маппинг в `docker-compose.yml`.

## Связанные репозитории

- Frontend: ../umirhak2026_front

## Статус

Учебный/демо проект реестра дипломов, развивается поэтапно.
