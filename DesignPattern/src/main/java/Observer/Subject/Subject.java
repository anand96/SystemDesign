package Observer.Subject;

import Observer.Observer.Observer;

public interface Subject {
    public void registerObserver(Observer o);
    public void removeObserver(Observer o);
    public void notifyObserver();
}
