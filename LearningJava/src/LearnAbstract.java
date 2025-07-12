
abstract class Parent{
    public Parent(){
        System.out.println("Base 1 constructor");
    }
    public void sayHello(){
        System.out.println("hello");
    }
    abstract public void greet();
}

class Child extends Parent{
    @Override
    public void greet() {
        System.out.println("Good Moring");
    }
}

abstract class Child3 extends Parent{

    public void th() {
        System.out.println("Good afternoon");
    }
}

public class LearnAbstract {
    public static void main(String[] args) {
        Child ch  =new Child();
    }
}
