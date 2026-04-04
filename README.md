# Diploma Verification Backend (MVP)

Backend на `Ktor + PostgreSQL + Redis` с 4 ролями: `super-admin`, `university`, `hr`, `student`.

## Модель данных (4 таблицы)

1. `students`
- `email`
- `full_name`
- `password_hash`

2. `hr_specialists`
- `email`
- `full_name`
- `password_hash`

3. `universities`
- `code`
- `name`
- `email`
- `contact_full_name`
- `password_hash`

4. `diploma_registry`
- `diploma_payload_hash` = hash(ФИО + код вуза + специальность + код диплома + год)
- `diploma_lookup_hash` = hash(код диплома + код вуза)
- статус и служебные поля

Дополнительно в `diploma_registry` хранятся зашифрованные данные (ФИО/специальность/код диплома) для QR-страницы.

## Запуск

```bash
./gradlew clean run
```

или

```bash
docker compose up --build
```

## Конфиг

Через `application.yaml` / env:
- `JDBC_URL`, `DB_USER`, `DB_PASSWORD`
- `REDIS_URL`
- `SUPERADMIN_LOGIN`, `SUPERADMIN_PASSWORD`
- `HASH_SALT`, `ENCRYPTION_KEY_BASE64`

## API

### Super-admin
- `POST /api/v1/admin/universities`
  - headers: `X-Superadmin-Login`, `X-Superadmin-Password`

### Student
- `POST /api/v1/students/register`
- `POST /api/v1/student/qr`
  - headers: `X-Student-Email`, `X-Student-Password`
- `POST /api/v1/student/qr/{token}/revoke`

### HR
- `POST /api/v1/hr/register`
- `GET /api/v1/hr/verify?universityCode=...&diplomaCode=...`
  - headers: `X-HR-Email`, `X-HR-Password`

### University
- `POST /api/v1/university/diplomas`
  - headers: `X-University-Code`, `X-University-Email`, `X-University-Password`
- `POST /api/v1/university/diplomas/upload`
  - multipart file `.csv/.xlsx`
- `POST /api/v1/university/diplomas/revoke?diplomaCode=...`

### Public verify
- `GET /api/v1/verify/qr/{token}`

---

## Postman: проверочный сценарий

### 1) Создать аккаунт ВУЗа (super-admin)
`POST http://localhost:8080/api/v1/admin/universities`

Headers:
- `X-Superadmin-Login: admin`
- `X-Superadmin-Password: admin123`
- `Content-Type: application/json`

Body:
```json
{
  "code": "MSU",
  "name": "Moscow State University",
  "email": "registrar@msu.ru",
  "contactFullName": "Ivan Petrov",
  "password": "msuPass123"
}
```

### 2) Зарегистрировать HR
`POST http://localhost:8080/api/v1/hr/register`

Body:
```json
{
  "email": "hr@company.com",
  "fullName": "Anna HR",
  "password": "hrPass123"
}
```

### 3) Зарегистрировать студента
`POST http://localhost:8080/api/v1/students/register`

Body:
```json
{
  "email": "student@mail.com",
  "fullName": "Alex Student",
  "password": "studPass123"
}
```

### 4) Добавить диплом от ВУЗа
`POST http://localhost:8080/api/v1/university/diplomas`

Headers:
- `X-University-Code: MSU`
- `X-University-Email: registrar@msu.ru`
- `X-University-Password: msuPass123`

Body:
```json
{
  "fullName": "Alex Student",
  "specialty": "Computer Science",
  "diplomaCode": "MSU-2026-0001",
  "graduationYear": 2026
}
```

### 5) Проверить диплом как HR
`GET http://localhost:8080/api/v1/hr/verify?universityCode=MSU&diplomaCode=MSU-2026-0001`

Headers:
- `X-HR-Email: hr@company.com`
- `X-HR-Password: hrPass123`

Ожидание: `verdict = GREEN`.

### 6) Получить QR студентом
`POST http://localhost:8080/api/v1/student/qr`

Headers:
- `X-Student-Email: student@mail.com`
- `X-Student-Password: studPass123`

Body:
```json
{
  "universityCode": "MSU",
  "diplomaCode": "MSU-2026-0001",
  "ttlMinutes": 30
}
```

Ожидание: `token`, `verifyUrl`, `qrBase64Png`.

### 7) Проверка QR (публично)
`GET http://localhost:8080/api/v1/verify/qr/{token}`

Ожидание: ФИО, специальность, университет, срок действия.

### 8) Аннулировать диплом ВУЗом
`POST http://localhost:8080/api/v1/university/diplomas/revoke?diplomaCode=MSU-2026-0001`

Headers как в шаге 4.

### 9) Повторно проверить HR
Шаг 5 повторно.

Ожидание: `verdict = RED`, причина `Diploma revoked`.
