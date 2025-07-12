package Observer.Subject;

import Observer.Observer.Observer;

import java.util.ArrayList;

public class WeatherData implements Subject{
    private ArrayList<Observer> observers;
    private float temperature;
    private float pressure;

    private float humidity;

    public WeatherData()
    {
        observers = new ArrayList<>();
    }

    @Override
    public void registerObserver(Observer o) {
        observers.add(o);

    }

    @Override
    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    @Override
    public void notifyObserver() {
        for (Observer o : observers) {
            o.update(temperature, humidity, pressure);
        }
    }

    public void measurementsChanged()
    {
        notifyObserver();
    }

    public void setMeasurements(float temperature, float humidity, float pressure)
    {
        this.temperature = temperature;
        this.humidity = humidity;
        this.pressure = pressure;
    }
}
