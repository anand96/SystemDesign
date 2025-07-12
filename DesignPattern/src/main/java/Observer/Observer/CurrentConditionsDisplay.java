package Observer.Observer;

public class CurrentConditionsDisplay implements Observer{
    private float temperature;
    private float humidity;
    @Override
    public void update(float temp, float humidity, float pressure) {
        this.temperature = temp;
        this.humidity = humidity;
        display();
    }

    public void display()
    {
        System.out.println("Current conditions: " + temperature + " humidity " + humidity);
    }
}
