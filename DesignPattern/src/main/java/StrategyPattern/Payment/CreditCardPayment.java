package StrategyPattern.Payment;

// Concrete Strategies
public class CreditCardPayment implements PaymentStrategy {
    private String cardNumber;
    public CreditCardPayment(String num) { cardNumber = num; }
    @Override public void pay(int amount) {
        System.out.println("Paid " + amount + " using Credit Card: " + cardNumber);
    }
}
