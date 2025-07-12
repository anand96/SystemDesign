package StrategyPattern.Payment;

// Usage
public class Main {
    public static void main(String[] args) {
        ShoppingCart cart = new ShoppingCart();
        cart.setPaymentStrategy(new CreditCardPayment("1234"));
        cart.checkout(100);
        cart.setPaymentStrategy(new PayPalPayment("john@example.com"));
        cart.checkout(50);
    }
}