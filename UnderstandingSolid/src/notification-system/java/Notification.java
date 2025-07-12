// Interface Segregation Principle : Clients should not be forced to depend on interfaces they do not use.

public interface Notification {
    void send(String message);
}