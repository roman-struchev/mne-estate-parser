package com.estate.parser.service.subscriptions;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import jakarta.annotation.PostConstruct;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionSender {

    @Value("${app.telegram.bot.token:}")
    private String telegramBotToken;

    @Value("${app.smtp.bot.host:}")
    private String smptHost;
    @Value("${app.smtp.bot.port:}")
    private String smptPort;
    @Value("${app.smtp.bot.login:}")
    private String smptLogin;
    @Value("${app.smtp.bot.password:}")
    private String smptPassword;

    private TelegramBot telegramBot;

    @Async
    public void sendToTelegram(List<String> telegramBotChatIds, String header, String content) {
        telegramBotChatIds.forEach(telegramBotChatId -> sendToTelegram(telegramBotChatId, header, content));
    }

    private void sendToTelegram(String telegramBotChatId, String header, String content) {
        try {
            if (this.telegramBot == null || StringUtils.isEmpty(telegramBotChatId)) {
                log.info("Skip sending to telegram for {}", telegramBotChatId);
                return;
            }
            var message = new SendMessage(telegramBotChatId, "*%s*\n\n%s".formatted(header, content));
            message.parseMode(ParseMode.Markdown);
            message.disableWebPagePreview(true);
            this.telegramBot.execute(message);
        } catch (Exception ex) {
            log.error("Can't send message to telegram {}", telegramBotChatId, ex);
        }
    }


    @Async
    public void sendToEmail(List<String> emails, String header, String content) {
        emails.forEach(email -> sendToEmail(email, header, content));
    }

    private void sendToEmail(String email, String header, String content) {
        try {
            if (StringUtils.isAnyEmpty(email, smptHost, smptPort, smptLogin, smptPassword)) {
                log.info("Skip sending to email {}", email);
                return;
            }

            var properties = System.getProperties();
            properties.put("mail.smtp.host", smptHost);
            properties.put("mail.smtp.port", smptPort);
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.starttls.enable", "true");

            var session = Session.getInstance(properties, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(smptLogin, smptPassword);
                }
            });

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smptLogin));
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(email));
            message.setSubject(header);
            message.setText(content);

            Transport.send(message);
        } catch (Exception ex) {
            log.error("Can't send message to email {}", email, ex);
        }
    }

    @PostConstruct
    private void init() {
        log.info("SMTP settings [{}, {}, {}, {}]", smptHost, smptPort, smptLogin, smptPassword.replaceAll(".", "*"));
        log.info("Telegram settings [{}]", telegramBotToken.replaceAll(".", "*"));

        if (StringUtils.isNoneEmpty(telegramBotToken)) {
            this.telegramBot = new TelegramBot(telegramBotToken);
            this.telegramBot.setUpdatesListener(updates -> {
                updates.forEach(update -> {
                    var chatId = update.message().chat().id();
                    var message = update.message().text();
                    if (message != null) {
                        log.info("Telegram group {}, message: {}", chatId, message);
                        var responseStr = String.format("Your chat id: %s\nOriginal message: %s", chatId, message);
                        telegramBot.execute(new SendMessage(update.message().chat().id(), responseStr));
                    }
                });
                return UpdatesListener.CONFIRMED_UPDATES_ALL;
            });
        }
    }
}
