package StrategyPattern.withStrategyPattern;

import StrategyPattern.withStrategyPattern.Strategy.DriveStrategy;
import StrategyPattern.withStrategyPattern.Strategy.SportsDriveStrategy;
import StrategyPattern.withStrategyPattern.Vehicle;

public class OffRoadVehicle extends Vehicle{

    OffRoadVehicle() {
        super(new SportsDriveStrategy());
    }
}

