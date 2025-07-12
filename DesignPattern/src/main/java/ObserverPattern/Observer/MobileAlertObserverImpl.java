package ObserverPattern.Observer;

import ObserverPattern.Observable.StockObservable;

public class MobileAlertObserverImpl implements NotificationAlertObserver{
    String username;
    StockObservable stockObservable;

    public MobileAlertObserverImpl(StockObservable stockObservable, String username)
    {
        this.stockObservable = stockObservable;
        this.username = username;
    }

    @Override
    public void update() {
        sendmsgOnMobile(username, "Product in stock");
    }

    public void sendmsgOnMobile(String username, String message)
    {
        System.out.println("Msg send to :"+ username);
    }
}
