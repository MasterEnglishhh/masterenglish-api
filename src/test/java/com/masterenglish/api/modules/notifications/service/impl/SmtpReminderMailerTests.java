package com.masterenglish.api.modules.notifications.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.ReminderMailException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpReminderMailerTests {

    private static NotificationProperties properties(String from) {
        return new NotificationProperties(
                "Asia/Ho_Chi_Minh",
                false,
                3,
                60,
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                200,
                20,
                new NotificationProperties.Mail(true, from, "https://app.example.test", ""));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    @Test
    @DisplayName("Thiếu JavaMailSender hoặc MAIL_FROM ném lỗi permanent MAIL_NOT_CONFIGURED")
    void missingSenderOrFromThrowsPermanentMailNotConfigured() {
        var noSender = new SmtpReminderMailer(provider(null), properties("noreply@example.test"));
        assertThatThrownBy(() -> noSender.send("a@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_NOT_CONFIGURED");
                            assertThat(ex.isPermanent()).isTrue();
                        });

        var noFrom = new SmtpReminderMailer(provider(mock(JavaMailSender.class)), properties(" "));
        assertThatThrownBy(() -> noFrom.send("a@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_NOT_CONFIGURED");
                            assertThat(ex.isPermanent()).isTrue();
                        });
    }

    @Test
    @DisplayName("Lỗi xác thực và lỗi gửi tạm thời giữ cờ retryable; lỗi chuẩn bị thư là permanent")
    void classifiesAuthPreparationAndSendFailures() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var mailer = new SmtpReminderMailer(provider(sender), properties("noreply@example.test"));

        doThrow(new MailAuthenticationException("bad auth"))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mailer.send("a@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_AUTH_FAILED");
                            assertThat(ex.isPermanent()).isFalse();
                        });

        doThrow(new MailSendException("smtp timeout"))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mailer.send("a@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_SEND_FAILED");
                            assertThat(ex.isPermanent()).isFalse();
                        });

        doThrow(new MailParseException("invalid address"))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mailer.send("a@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_SEND_FAILED");
                            assertThat(ex.isPermanent()).isTrue();
                        });

        doThrow(new MailPreparationException("bad payload"))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mailer.send("a@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_SEND_FAILED");
                            assertThat(ex.isPermanent()).isTrue();
                        });
    }

    @Test
    @DisplayName("Gửi email thành công gán đúng from, to, subject, text")
    void sendsSimpleMailMessageWithExpectedFields() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var mailer = new SmtpReminderMailer(provider(sender), properties("noreply@example.test"));

        String messageId = mailer.send("learner@example.test", "Nhắc ôn tập", "Nội dung");

        assertThat(messageId).isNull();
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getFrom()).isEqualTo("noreply@example.test");
        assertThat(msg.getTo()).containsExactly("learner@example.test");
        assertThat(msg.getSubject()).isEqualTo("Nhắc ôn tập");
        assertThat(msg.getText()).isEqualTo("Nội dung");
    }

    @Test
    @DisplayName("SMTP 5xx và địa chỉ bị từ chối vĩnh viễn đánh dấu permanent; 4xx giữ retryable")
    void classifiesSmtp5xxAsPermanentAnd4xxAsRetryable() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        var mailer = new SmtpReminderMailer(provider(sender), properties("noreply@example.test"));

        var invalidAddressCause =
                new jakarta.mail.SendFailedException(
                        "550 5.1.1 User unknown",
                        null,
                        new jakarta.mail.Address[0],
                        new jakarta.mail.Address[0],
                        new jakarta.mail.Address[] {
                            new jakarta.mail.internet.InternetAddress("bounced@example.test")
                        });
        doThrow(new MailSendException("delivery failed", invalidAddressCause))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mailer.send("bounced@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_SEND_FAILED");
                            assertThat(ex.isPermanent()).isTrue();
                        });

        doThrow(new MailSendException("smtp 550", new FakeSmtpCodeException(550)))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mailer.send("bounced@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_SEND_FAILED");
                            assertThat(ex.isPermanent()).isTrue();
                        });

        doThrow(new MailSendException("smtp 421", new FakeSmtpCodeException(421)))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mailer.send("temp@example.test", "Subj", "Body"))
                .isInstanceOfSatisfying(
                        ReminderMailException.class,
                        ex -> {
                            assertThat(ex.getFailureCode()).isEqualTo("MAIL_SEND_FAILED");
                            assertThat(ex.isPermanent()).isFalse();
                        });
    }

    private static final class FakeSmtpCodeException extends Exception {
        private final int returnCode;

        FakeSmtpCodeException(int returnCode) {
            this.returnCode = returnCode;
        }

        @SuppressWarnings("unused")
        public int getReturnCode() {
            return returnCode;
        }
    }
}
