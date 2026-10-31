package cc.hyoban.moments.sidekey;
/** Pure timing state machine. Times must use a monotonic clock. */
public final class GestureDetector {
 public interface Output { void fire(String gesture); }
 private final Output output;
 private long downAt,firstUp;
 private boolean down,held,pending;
 public long longMs=600,doubleMs=300;
 public GestureDetector(Output output){this.output=output;}
 public long nextDeadline(){if(down&&!held)return downAt+longMs;if(pending&&!down)return firstUp+doubleMs;return Long.MAX_VALUE;}
 public void reset(){down=false;held=false;pending=false;}
 public void tick(long now){
  if(down&&!held&&now-downAt>=longMs){held=true;pending=false;output.fire("long");}
  if(pending&&!down&&now-firstUp>=doubleMs){pending=false;output.fire("single");}
 }
 public void event(boolean pressed,long now){
  tick(now);
  if(pressed){if(down)return;down=true;held=false;downAt=now;}
  else {if(!down)return;down=false;
   if(held){held=false;return;}
   if(pending&&now-firstUp<=doubleMs){pending=false;output.fire("double");}
   else {if(pending)output.fire("single");pending=true;firstUp=now;}
  }
 }
}
