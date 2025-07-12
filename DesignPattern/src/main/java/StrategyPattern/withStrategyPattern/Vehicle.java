package StrategyPattern.withStrategyPattern;

import StrategyPattern.withStrategyPattern.Strategy.DriveStrategy;

public class Vehicle {
    DriveStrategy driveStrategy;

    Vehicle(DriveStrategy driveStrategy)
    {
        this.driveStrategy = driveStrategy;
    }

    public void drive()
    {
        driveStrategy.drive();
    }
}
