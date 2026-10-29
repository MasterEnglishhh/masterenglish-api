package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.config.RegistrationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class RegistrationMailer {
    private final ObjectProvider<JavaMailSender> sender;
    private final RegistrationProperties properties;

    public RegistrationMailer(
            ObjectProvider<JavaMailSender> sender, RegistrationProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public void ensureReady() {
        if (!properties.mailEnabled() || sender.getIfAvailable() == null) throw unavailable();
    }

    /** Called only after the database transaction commits. The raw code is never persisted. */
    public void send(String email, String otp) {
        ensureReady();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject("Mã xác minh tài khoản MasterEnglish");
        message.setText(
                "Mã xác minh của bạn là: "
                        + otp
                        + "\n\nMã có hiệu lực trong 5 phút và chỉ dùng một lần. Chỉ nhập mã vào lượt đăng ký bạn vừa bắt đầu.\nNếu bạn không đăng ký MasterEnglish, hãy bỏ qua email này.\n\nMasterEnglish");
        deliver(message);
    }

    public void sendPasswordReset(String email, String otp) {
        ensureReady();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject("Mã đặt lại mật khẩu MasterEnglish");
        message.setText(
                "Mã xác minh đặt lại mật khẩu của bạn là: "
                        + otp
                        + "\n\nMã có hiệu lực trong 5 phút và chỉ dùng một lần. Chỉ nhập mã vào yêu cầu quên mật khẩu bạn vừa bắt đầu.\nNếu không yêu cầu đặt lại mật khẩu, bạn có thể bỏ qua email này; mật khẩu hiện tại chưa thay đổi.\n\nMasterEnglish");
        deliver(message);
    }

    public void sendPasswordResetNotice(String email) {
        ensureReady();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject("Mật khẩu MasterEnglish đã được cập nhật");
        message.setText(
                "Mật khẩu tài khoản MasterEnglish của bạn vừa được đặt lại. Các phiên đăng nhập cũ đã được thu hồi.\n\nNếu không thực hiện thao tác này, hãy truy cập chức năng quên mật khẩu để bảo vệ tài khoản của bạn.\n\nMasterEnglish");
        deliver(message);
    }

    private void deliver(SimpleMailMessage message) {
        try {
            sender.getObject().send(message);
        } catch (MailException e) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Chưa thể gửi email xác minh. Vui lòng thử lại sau.");
    }
}
