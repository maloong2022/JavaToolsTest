/**
 * staticObject, instanceObj, localObj 存放在哪里？ VM Args: -Xmx10m -XX:+UseSerialGC
 * -XX:-UseCompressedOops
 */
public class JHSDB_TestCase {
  public static void main(String[] args) throws InterruptedException {
    Test test = new JHSDB_TestCase.Test();
    test.foo();
  }

  static class Test {
    static ObjectHolder staticObj = new ObjectHolder();

    ObjectHolder instanceObj = new ObjectHolder();

    void foo() throws InterruptedException {
      ObjectHolder localObj = new ObjectHolder();
      System.out.println("done");
      Thread.sleep(60 * 60 * 24 * 24); // 方便有时间查看 jhsdb
    }
  }

  private static class ObjectHolder {}
}
