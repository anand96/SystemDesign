//Single Responsibility Principle : Each class should have one and only one reason to change.
public class Logger {
    public void log(String info)
    {
        System.out.println("Log :" + info);
    }
}
