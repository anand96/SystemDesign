package Observer;

import Observer.Observer.CurrentConditionsDisplay;
import Observer.Subject.WeatherData;

public class WeatherStation {
    public static void main(String args[]) {
        WeatherData weatherData = new WeatherData();

        CurrentConditionsDisplay currentConditionsDisplay = new CurrentConditionsDisplay();

        weatherData.registerObserver(currentConditionsDisplay);

        // Simulate new weather measurements
        weatherData.setMeasurements(80, 65, 30.4f);
        weatherData.setMeasurements(82, 70, 29.2f);
        weatherData.setMeasurements(78, 90, 29.2f);
    }
}
