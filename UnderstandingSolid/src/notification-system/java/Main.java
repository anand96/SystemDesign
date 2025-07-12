import java.util.Arrays;

public class Main {
    public static void main(String args[])
    {
        Notification email = new EmailNotification();
        Notification sms = new SMSNotification();

        Logger logger = new Logger();

        NotificationService service = new NotificationService(Arrays.asList(email, sms), logger);
        service.sendNotifications("Hello Beta learn Solid......");
    }
}
