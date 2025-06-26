/**
 * staticObject, instanceObj, localObj 存放在哪里？
 */
public class JHSDB_TestCase {
  static class Test {
    static ObjectHolder staticObj = new ObjectHolder();

    ObjectHolder instanceObj = new ObjectHolder();

    void foo() {
      ObjectHolder localObj = new ObjectHolder();
      System.out.println("done");// 此处设置一个断点
    }
  }
}
