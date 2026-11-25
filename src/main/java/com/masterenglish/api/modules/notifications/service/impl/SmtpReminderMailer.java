package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.ReminderMailException;
import com.masterenglish.api.modules.notifications.service.ReminderMailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpReminderMailer implements ReminderMailer {

    private final ObjectProvider<JavaMailSender> sender;
    private final NotificationProperties properties;

    public SmtpReminderMailer(
            ObjectProvider<JavaMailSender> sender, NotificationProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public String send(String recipient, String subject, String text) {
        JavaMailSender mailSender = sender.getIfAvailable();
        if (mailSender == null || properties.mail().from().isBlank()) {
            throw ReminderMailException.permanent("MAIL_NOT_CONFIGURED");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
            return null;
        } catch (MailAuthenticationException exception) {
            throw new ReminderMailException("MAIL_AUTH_FAILED");
        } catch (MailParseException | MailPreparationException exception) {
            throw ReminderMailException.permanent("MAIL_SEND_FAILED");
        } catch (MailException exception) {
            throw new ReminderMailException("MAIL_SEND_FAILED");
        }
    }
}
