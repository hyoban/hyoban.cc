package cc.hyoban.moments.sidekey;
import org.junit.Test;
import java.util.*;
public class SideKeyGesturesTest {
 static List<String> out;static GestureDetector g;
 static void init(){out=new ArrayList<>();g=new GestureDetector(out::add);}
 static void expected(String... values){if(!out.equals(Arrays.asList(values)))throw new AssertionError(out+" != "+Arrays.toString(values));}
 @Test public void recognizesMutuallyExclusiveGestures(){
  init();g.event(true,0);g.event(false,172);g.tick(471);expected();g.tick(472);expected("single");
  init();g.event(true,0);g.event(false,141);g.event(true,258);g.event(false,383);g.tick(2000);expected("double");
  init();g.event(true,0);g.tick(599);expected();g.tick(600);g.tick(2000);g.event(false,2555);g.tick(4000);expected("long");
  init();g.event(true,0);g.event(false,60);g.event(true,400);g.event(false,460);g.tick(800);expected("single","single");
  init();g.event(true,0);g.event(true,40);g.event(false,100);g.event(false,120);g.tick(500);expected("single");
  init();g.event(true,0);g.reset();g.event(false,100);g.tick(1000);expected();
  init();g.event(true,0);g.event(false,60);g.event(true,180);g.tick(780);g.event(false,900);g.tick(1200);expected("long");
  init();g.event(true,0);g.event(false,900);g.tick(1300);expected("long");
  init();g.event(true,0);g.event(false,100);g.event(true,200);g.event(false,300);g.event(true,450);g.event(false,500);g.tick(900);expected("double","single");
  System.out.println("PASS: 9 gesture timing scenarios");
 }
}
