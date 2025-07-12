package StrategyPattern.Payment;

// Context
public class ShoppingCart {
    private PaymentStrategy strategy;
    public void setPaymentStrategy(PaymentStrategy s) { strategy = s; }
    public void checkout(int amount) { strategy.pay(amount); }
}