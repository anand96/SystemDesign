package StrategyPattern.withStrategyPattern;

import StrategyPattern.withStrategyPattern.Strategy.NormalDriveStrategy;

public class GoodsVehicle extends Vehicle{
    GoodsVehicle() {
        super(new NormalDriveStrategy());
    }
}
