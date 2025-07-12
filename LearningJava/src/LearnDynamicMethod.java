
class One{

    public void greet() {
        System.out.println("Good Morning");
    }
    public void name(){
        System.out.println("My name is java");
    }
}

class Two extends One{
    public void swagat () {
        System.out.println("Apka swagat hai");
    }

    public void name(){
        System.out.println("My name is java in class two");
    }
}



public class LearnDynamicMethod {
    public static void main(String[] args) {
        One obj = new Two(); // Runtime Polymerphism
        obj.greet();
        obj.name();

    }
}
