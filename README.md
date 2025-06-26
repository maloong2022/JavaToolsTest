# Java Tools Test

### JHSDB：基于服务性代理的调试工具

![](images/1.png)JCMD、JHSDB和基础工具的对比

JHSDB是一款基于服务性代理(Serviceability Agent，SA)实现的进程外调试工具。服务性代理是HotSpot虚拟机中一组用于映射Java虚拟机运行信息的、主要基于Java语言（含少量JNI代码）实现的API集合。服务性代理以HotSpot内部的数据结构为参照物进行设计，把这些C++的数据抽象出Java模型对象，相当于HotSpot的C++代码的一个镜像。通过服务性代理的API，可以在一个独立的Java虚拟机的进程里分析其他HotSpot虚拟机的内部数据，或者从HotSpot虚拟机进程内存中dump出来的转储快照里还原出它的运行状态细节。服务性代理的工作原理跟Linux上的GDB或者Windows上的Windbg是相似的。本次，我们要借助JHSDB来分析一下代码清单中的代码，并通过实验来回答一个简单问题：<font color="#ff0000">staticObj、instanceObj、localObj这三个变量本身（而不是它们所指向的对象）存放在哪里？</font>
```java
/**  
 * staticObject, instanceObj, localObj 存放在哪里？ VM Args: -Xmx10m -XX:+UseSerialGC * -XX:-UseCompressedOops 
 * */
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
      Thread.sleep(60 * 60 * 24 * 24);  //方便有时间查看 jhsdb
    }  
  }  
  
  private static class ObjectHolder {}  
}
```
答案读者当然都知道：staticObj随着Test的类型信息存放在方法区，instanceObj随着Test的对象实例存放在Java堆，localObject则是存放在foo()方法栈帧的局部变量表中。

首先，我们要确保这三个变量已经在内存中分配好，然后将程序暂停下来，以便有空隙进行实验，这只要把断点设置在代码中加粗的打印语句上，然后在调试模式下运行程序即可。由于JHSDB本身对压缩指针的支持存在很多缺陷，建议用64位系统的读者在实验时禁用压缩指针，另外为了后续操作时可以加快在内存中搜索对象的速度，也建议读者限制一下Java堆的大小。本例中，笔者采用的运行在 mac Java11 上，参数如下：
```bash
-Xmx10m  -XX:+UseSerialGC   -XX:-UseCompressedOops
```

```cardlink
url: https://blog.csdn.net/x_iya/article/details/123091705
title: "解决 hsdb jinfo jmap sa-jdi等mac不可用问题_could be caused by an incorrect pid or lack of pri-CSDN博客"
description: "文章浏览阅读3.6k次，点赞3次，收藏10次。mac 使用 hsdb 调试的时候报错hsdb> attach 3196Attaching to process 3196, please wait...ERROR: attach: task_for_pid(3196) failed: '(os/kern) failure' (5)Unable to connect to process ID 3196:sun.jvm.hotspot.debugger.DebuggerException: Can't attach to the proc_could be caused by an incorrect pid or lack of privileges"
host: blog.csdn.net
```

程序执行后通过jps查询到测试程序的进程ID，具体如下：
```bash
jps -l                                                                                                                                                 (base)
85729 com.intellij.idea.Main
24169 jdk.jcmd/sun.tools.jps.Jps
21211 org.jetbrains.jps.cmdline.Launcher
21212 JHSDB_TestCase
```

使用以下命令进入JHSDB的图形化模式，并使其附加进程21212：
```bash
jhsdb hsdb --pid 21212
```

命令打开的JHSDB的界面如图:

![](images/2.png)

阅读代码可知一共会创建三个ObjectHolder对象的实例，只要是对象实例必然会在Java堆中分配，既然我们要查找引用这三个对象的指针存放在哪里，不妨从这三个对象开始着手，先把它们从Java堆中找出来。

首先点击菜单中的Tools->Heap Parameters，结果如图所示，因为笔者的运行参数中指定了使用的是Serial收集器，图中我们看到了典型的Serial的分代内存布局，Heap Parameters窗口中清楚列出了新生代的Eden、S1、S2和老年代的容量（单位为字节）以及它们的虚拟内存地址起止范围。
![](images/3.png)
打开Windows->Console窗口，使用scanoops命令在Java堆的新生代（从Eden起始地址到To Survivor结束地址）范围内查找ObjectHolder的实例，结果如下所示：
![](images/4.png)

果然找出了三个实例的地址，而且它们的地址都落到了Eden的范围之内，算是顺带验证了一般情况下新对象在Eden中创建的分配规则。

接下来要根据堆中对象实例地址找出引用它们的指针，原本JHSDB的Tools菜单中有Compute Reverse Ptrs来完成这个功能，但在笔者的运行环境中一点击它就出现Swing的界面异常，看后台日志是报了个空指针，这个问题只是界面层的异常，跟虚拟机关系不大，所以笔者没有继续去深究，改为使用命令来做也很简单，先拿第一个对象来试试看：
```bash
hsdb> revptrs 0x0000000107313598
null
Oop for java/lang/Class @ 0x00000001073122a0
```
果然找到了一个引用该对象的地方，是在一个java.lang.Class的实例里，并且给出了这个实例的地址，通过Inspector查看该对象实例，可以清楚看到这确实是一个java.lang.Class类型的对象实例，里面有一个名为staticObj的实例字段，如图所示。
![](images/5.png)]
从《Java虚拟机规范》所定义的概念模型来看，所有Class相关的信息都应该存放在方法区之中，但方法区该如何实现，​《Java虚拟机规范》并未做出规定，这就成了一件允许不同虚拟机自己灵活把握的事情。JDK 7及其以后版本的HotSpot虚拟机选择把静态变量与类型在Java语言一端的映射Class对象存放在一起，存储于Java堆之中，从我们的实验中也明确验证了这一点。接下来继续查找第二个对象实例：
```bash
hsdb> revptrs 0x00000001073135c0
Oop for JHSDB_TestCase$Test @ 0x00000001073135a8
```

这次找到一个类型为JHSDB_TestCase$Test的对象实例，在Inspector中该对象实例显示如图所示。
![](images/6.png)

这个结果完全符合我们的预期，第二个ObjectHolder的指针是在Java堆中JHSDB_TestCase$Test对象的instanceObj字段上。但是我们采用相同方法查找第三个ObjectHolder实例时，JHSDB返回了一个null，表示未查找到任何结果：
```bash
hsdb> revptrs 0x00000001073135d0
null
```
看来revptrs命令并不支持查找栈上的指针引用，不过没有关系，得益于我们测试代码足够简洁，人工也可以来完成这件事情。在Java Thread窗口选中main线程后点击Stack Memory按钮查看该线程的栈内存，如图所示。
![](images/7.png)

这个线程只有两个方法栈帧，尽管没有查找功能，但通过肉眼观察在地址0x0000...16db1aab0上的值正好就是0x00000001073135d0，而且JHSDB在旁边已经自动生成注释，说明这里确实是引用了一个来自新生代的JHSDB_TestCase$ObjectHolder对象。

### JConsole：Java监视与管理控制台

JConsole(Java Monitoring and ManagementConsole)是一款基于JMX(Java Manage-mentExtensions)的可视化监视、管理工具。它的主要功能是通过JMX的MBean(Managed Bean)对系统进行信息收集和参数动态调整。JMX是一种开放性的技术，不仅可以用在虚拟机本身的管理上，还可以运行于虚拟机之上的软件中，典型的如中间件大多也基于JMX来实现管理与监控。虚拟机对JMX MBean的访问也是完全开放的，可以使用代码调用API、支持JMX协议的管理控制台，或者其他符合JMX规范的软件进行访问。

#### 内存监控
“内存”页签的作用相当于可视化的jstat命令，用于监视被收集器管理的虚拟机内存（被收集器直接管理的Java堆和被间接管理的方法区）的变化趋势。我们通过运行代码清单4-7中的代码来体验一下它的监视功能。运行时设置的虚拟机参数为：
```bash
-Xms100m -Xmx100m -XX:+UseSerialGC
```
代码【JConsoleMonitorMemory.java】

这段代码的作用是以64KB/50ms的速度向Java堆中填充数据，一共填充1000次，使用JConsole的“内存”页签进行监视，观察曲线和柱状指示图的变化。程序运行后，在“内存”页签中可以看到内存池Eden区的运行趋势呈现折线状，如图所示。监视范围扩大至整个堆后，会发现曲线是一直平滑向上增长的。从柱状图可以看到，在1000次循环执行结束，运行了System.gc()后，虽然整个新生代Eden和Survivor区都基本被清空了，但是代表老年代的柱状图仍然保持峰值状态，说明被填充进堆中的数据在System.gc()方法执行之后仍然存活。

1. 虚拟机启动参数只限制了Java堆为100MB，但没有明确使用-Xmn参数指定新生代大小，读者能否从监控图中估算出新生代的容量？
2. 为何执行了System.gc()之后，图中代表老年代的柱状图仍然显示峰值状态，代码需要如何调整才能让System.gc()回收掉填充到堆中的对象？
![](images/8.png)

问题1答案：图显示Eden空间为28416KB，因为没有设置-XX：SurvivorRadio参数，所以Eden与Survivor空间比例的默认值为8∶1，因此整个新生代空间大约为28416KB×125%=35520KB。

问题2答案：执行System.gc()之后，空间未能回收是因为$List<OOMObject>l$ist对象仍然存活，fillHeap()方法仍然没有退出，因此list对象在System.gc()执行时仍然处于作用域之内。如果把System.gc()移动到fillHeap()方法外调用就可以回收掉全部内存。

#### 线程监控
如果说JConsole的“内存”页签相当于可视化的jstat命令的话，那“线程”页签的功能就相当于可视化的jstack命令了，遇到线程停顿的时候可以使用这个页签的功能进行分析。前面讲解jstack命令时提到线程长时间停顿的主要原因有等待外部资源（数据库连接、网络资源、设备资源等）​、死循环、锁等待等，以下代码将分别演示这几种情况。

代码【JConsoleMonitorThreadCycleDeathAndThreadWaitForLock.java】

程序运行后，首先在“线程”页签中选择main线程，如图所示。堆栈追踪显示BufferedReader的readBytes()方法正在等待System.in的键盘输入，这时候线程为Runnable状态，Runnable状态的线程仍会被分配运行时间，但readBytes()方法检查到流没有更新就会立刻归还执行令牌给操作系统，这种等待只消耗很小的处理器资源。
![](images/9.png)

在终端输入任意字符：
接着监控testBusyThread线程，如图所示。testBusyThread线程一直在执行空循环，从堆栈追踪中看到一直在MonitoringTest.java代码的14行停留，14行的代码为while(true)。这时候线程为Runnable状态，<font color="#ff0000">而且没有归还线程执行令牌的动作，所以会在空循环耗尽操作系统分配给它的执行时间，直到线程切换为止，这种等待会消耗大量的处理器资源。</font>
![](images/10.png)

在终端输入任意字符：
如下图显示testLockThread线程在等待lock对象的notify()或notifyAll()方法的出现，线程这时候处于WAITING状态，在重新唤醒前不会被分配执行时间。
![](images/11.png)
testLockThread线程正处于正常的活锁等待中，只要<font color="#ff0000">lock对象</font>的notify()或notifyAll()方法被调用，这个线程便能激活继续执行。

#### 死锁代码样例
代码【JConsoleMonitorDeathLock.java】
这段代码开了200个线程去分别计算1+2以及2+1的值，理论上for循环都是可省略的，两个线程也可能会导致死锁，不过那样概率太小，需要尝试运行很多次才能看到死锁的效果。如果运气不是特别差的话，上面带for循环的版本最多运行两三次就会遇到线程死锁，程序无法结束。造成死锁的根本原因是Integer.valueOf()方法出于减少对象创建次数和节省内存的考虑，会对数值为-128～127之间的Integer对象进行缓存[插图]，如果valueOf()方法传入的参数在这个范围之内，就直接返回缓存中的对象。也就是说代码中尽管调用了200次Integer.valueOf()方法，但一共只返回了两个不同的Integer对象。假如某个线程的两个synchronized块之间发生了一次线程切换，那就会出现线程A在等待被线程B持有的Integer.valueOf(1)，线程B又在等待被线程A持有的Integer.valueOf(2)，结果大家都跑不下去的情况。
出现线程死锁之后，点击JConsole线程面板的“<font color="#ff0000">检测到死锁</font>”按钮，将出现一个新的“死锁”页签，如图所示。
![](images/12.png)

图中很清晰地显示，线程Thread-66在等待一个被线程Thread-67持有的Integer对象，而点击线程Thread-67则显示它也在等待一个被线程Thread-66持有的Integer对象，这样两个线程就互相卡住，除非牺牲其中一个，否则死锁无法释放。


