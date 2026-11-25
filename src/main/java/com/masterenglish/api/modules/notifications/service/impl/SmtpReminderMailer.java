package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.ReminderMailException;
import com.masterenglish.api.modules.notifications.service.ReminderMailer;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends reminder emails via Spring {@link JavaMailSender}. Raw SMTP {@link SimpleMailMessage}
 * transport does not return a provider receipt ID, so {@link #send} returns {@code null}.
 */
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
        } catch (MailSendException exception) {
            if (isPermanentSmtpFailure(exception)) {
                throw ReminderMailException.permanent("MAIL_SEND_FAILED");
            }
            throw new ReminderMailException("MAIL_SEND_FAILED");
        } catch (MailException exception) {
            throw new ReminderMailException("MAIL_SEND_FAILED");
        }
    }

    private static boolean isPermanentSmtpFailure(MailSendException root) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (containsPermanentCause(root, visited)) {
            return true;
        }
        for (Exception nested : root.getMessageExceptions()) {
            if (containsPermanentCause(nested, visited)) {
                return true;
            }
        }
        for (Exception failed : root.getFailedMessages().values()) {
            if (containsPermanentCause(failed, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPermanentCause(Throwable current, Set<Throwable> visited) {
        while (current != null && visited.add(current)) {
            if (current instanceof AddressException) {
                return true;
            }
            if (current instanceof SendFailedException sendFailed
                    && sendFailed.getInvalidAddresses() != null
                    && sendFailed.getInvalidAddresses().length > 0) {
                return true;
            }
            Integer returnCode = extractSmtpReturnCode(current);
            if (returnCode != null && returnCode >= 500 && returnCode < 600) {
                return true;
            }
            if (current instanceof MessagingException messaging
                    && messaging.getNextException() != null
                    && containsPermanentCause(messaging.getNextException(), visited)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Integer extractSmtpReturnCode(Throwable throwable) {
        try {
            Method method = throwable.getClass().getMethod("getReturnCode");
            if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) {
                return (Integer) method.invoke(throwable);
            }
        } catch (ReflectiveOperationException ignored) {
            // Not an SMTP exception exposing getReturnCode().
        }
        return null;
    }
}
