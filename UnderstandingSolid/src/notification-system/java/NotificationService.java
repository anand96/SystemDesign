// Single Responsibility Principle (SRP)
// Each class should have one and only one reason to change.
// Dependency Inversion Principle (DIP)
// Depend on abstractions, not on concretions.

import java.util.List;

public class NotificationService {
    private final List<Notification> notificationChannels;
    private final Logger logger;

    public NotificationService(List<Notification> notificationChannels, Logger logger) {
        this.notificationChannels = notificationChannels;
        this.logger = logger;
    }

    public void sendNotifications(String message)
    {
        for(Notification channels : notificationChannels)
        {
            channels.send(message);
        }
        logger.log("Notification send successfully");
    }
}
