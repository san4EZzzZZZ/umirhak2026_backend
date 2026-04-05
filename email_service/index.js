const express = require("express");
const nodemailer = require("nodemailer");

const app = express();
app.use(express.json({ limit: "256kb" }));

const port = Number(process.env.PORT || 8090);
const internalToken = String(process.env.INTERNAL_SERVICE_TOKEN || "").trim();

const smtpHost = String(process.env.SMTP_HOST || "smtp.yandex.ru").trim();
const smtpPort = Number(process.env.SMTP_PORT || 465);
const smtpSecure = String(process.env.SMTP_SECURE || "true").toLowerCase() === "true";
const smtpUser = String(process.env.SMTP_USER || "").trim();
const smtpPassword = String(process.env.SMTP_PASSWORD || "").trim();
const smtpRejectUnauthorized = String(process.env.SMTP_TLS_REJECT_UNAUTHORIZED || "true").toLowerCase() === "true";

const fromEmail = String(process.env.MAIL_FROM_EMAIL || smtpUser).trim();
const fromName = String(process.env.MAIL_FROM_NAME || "DIASOFT").trim();

if (!internalToken) {
  console.warn("INTERNAL_SERVICE_TOKEN is empty. Internal API is not protected.");
}
if (!smtpUser || !smtpPassword) {
  console.warn("SMTP credentials are missing. Email sending will fail until SMTP_USER/SMTP_PASSWORD are set.");
}

const transporter = nodemailer.createTransport({
  host: smtpHost,
  port: smtpPort,
  secure: smtpSecure,
  auth: {
    user: smtpUser,
    pass: smtpPassword,
  },
  tls: {
    rejectUnauthorized: smtpRejectUnauthorized,
  },
});

function isAuthorized(req) {
  if (!internalToken) return true;
  const header = String(req.header("x-internal-token") || "").trim();
  return header && header === internalToken;
}

app.get("/health", (_req, res) => {
  res.json({ status: "ok" });
});

app.post("/internal/mail/password-reset", async (req, res) => {
  if (!isAuthorized(req)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }

  const toEmail = String(req.body?.toEmail || "").trim();
  const resetLink = String(req.body?.resetLink || "").trim();
  const accountRole = String(req.body?.accountRole || "").trim();
  const ttlMinutes = Number(req.body?.ttlMinutes || 0);

  if (!toEmail || !resetLink) {
    res.status(400).json({ error: "toEmail and resetLink are required" });
    return;
  }

  const subject = "Сброс пароля в DIASOFT";
  const text = [
    "Вы запросили сброс пароля для почты.",
    "",
    `Ссылка для сброса: ${resetLink}`,
    ttlMinutes > 0 ? `Ссылка действует ${ttlMinutes} минут.` : null,
    "",
    "Если это были не вы, просто проигнорируйте это письмо.",
  ]
    .filter(Boolean)
    .join("\n");

  const html = `
    <div style="font-family:Arial,sans-serif;line-height:1.5;color:#1d1d1f">
      <h2 style="margin:0 0 12px">Сброс пароля в DIASOFT</h2>
      <p style="margin:0 0 12px">Вы запросили сброс пароля для данной почты.</p>
      <p style="margin:0 0 16px">
        <a href="${resetLink}" style="display:inline-block;padding:10px 14px;background:#1e5886;color:#fff;text-decoration:none;border-radius:8px">
          Сбросить пароль
        </a>
      </p>
      <p style="margin:0 0 8px">Или откройте ссылку вручную:</p>
      <p style="margin:0 0 12px;word-break:break-all"><a href="${resetLink}">${resetLink}</a></p>
      ${ttlMinutes > 0 ? `<p style="margin:0 0 12px">Срок действия ссылки: ${ttlMinutes} минут.</p>` : ""}
      <p style="margin:0;color:#666">Если это были не вы, просто проигнорируйте это письмо.</p>
    </div>
  `;

  try {
    await transporter.sendMail({
      from: fromName ? `"${fromName}" <${fromEmail}>` : fromEmail,
      to: toEmail,
      subject,
      text,
      html,
    });
    res.json({ sent: true });
  } catch (error) {
    console.error("Failed to send password reset email:", error);
    res.status(502).json({ error: "smtp send failed" });
  }
});

app.post("/internal/mail/admin-login-code", async (req, res) => {
  if (!isAuthorized(req)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }

  const toEmail = String(req.body?.toEmail || "").trim();
  const code = String(req.body?.code || "").trim();
  const ttlMinutes = Number(req.body?.ttlMinutes || 0);

  if (!toEmail || !code) {
    res.status(400).json({ error: "toEmail and code are required" });
    return;
  }

  const subject = "Код входа администратора DIASOFT";
  const text = [
    "Код подтверждения входа в админ-кабинет:",
    "",
    code,
    "",
    ttlMinutes > 0 ? `Код действует ${ttlMinutes} минут.` : null,
    "Если вы не запрашивали код, проигнорируйте это письмо.",
  ]
    .filter(Boolean)
    .join("\n");

  const html = `
    <div style="font-family:Arial,sans-serif;line-height:1.5;color:#1d1d1f">
      <h2 style="margin:0 0 12px">Код входа администратора DIASOFT</h2>
      <p style="margin:0 0 8px">Введите код подтверждения:</p>
      <p style="margin:0 0 14px;font-size:28px;letter-spacing:4px;font-weight:700">${code}</p>
      ${ttlMinutes > 0 ? `<p style="margin:0 0 12px">Срок действия кода: ${ttlMinutes} минут.</p>` : ""}
      <p style="margin:0;color:#666">Если вы не запрашивали код, просто проигнорируйте письмо.</p>
    </div>
  `;

  try {
    await transporter.sendMail({
      from: fromName ? `"${fromName}" <${fromEmail}>` : fromEmail,
      to: toEmail,
      subject,
      text,
      html,
    });
    res.json({ sent: true });
  } catch (error) {
    console.error("Failed to send admin login code email:", error);
    res.status(502).json({ error: "smtp send failed" });
  }
});

app.listen(port, () => {
  console.log(`Email service listening on port ${port}`);
});


