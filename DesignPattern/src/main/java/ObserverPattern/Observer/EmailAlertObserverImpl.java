package ObserverPattern.Observer;

import ObserverPattern.Observable.StockObservable;

public class EmailAlertObserverImpl implements NotificationAlertObserver{
    String emailId;
    StockObservable stockObservable;

    public EmailAlertObserverImpl(String emailId, StockObservable stockObservable)
    {
        this.stockObservable = stockObservable;
        this.emailId = emailId;
    }

    @Override
    public void update() {
        sendMail(emailId, "Product is in stock hurry up");
    }

    private void sendMail(String emailId, String message)
    {
        System.out.println("mail send to : " + emailId);
    }
}
