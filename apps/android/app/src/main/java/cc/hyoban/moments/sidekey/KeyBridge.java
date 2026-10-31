package cc.hyoban.moments.sidekey;
import java.io.*;
import android.os.SystemClock;
public class KeyBridge extends IKeyBridge.Stub {
 private Process reader;
 private volatile int generation;
 private IKeyCallback callback;
 private String run(String... args) throws Exception {
  Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  byte[] buffer=new byte[4096];int count;try(InputStream input=p.getInputStream()){while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);}
  String out=bytes.toString("UTF-8").trim();
  if(p.waitFor()!=0) throw new IOException(out);
  return out;
 }
 public synchronized void start(IKeyCallback cb) {
  stop(); callback=cb; final int gen=++generation;
  new Thread(()->{
   try {
    String inventory=run("getevent","-lp");
    String device=null,current=null;
    for(String line:inventory.split("\n")) {
     if(line.startsWith("add device")) current=line.substring(line.indexOf(":")+1).trim();
     if(line.contains("BTN_TRIGGER_HAPPY32")) { device=current; break; }
    }
    if(device==null) throw new IOException("Side key input device not found");
    final Process activeReader;
    synchronized(this) {
     if(gen!=generation)return;
     reader=new ProcessBuilder("getevent","-lt",device).redirectErrorStream(true).start();
     activeReader=reader;
    }
    cb.status(0);
    try(BufferedReader br=new BufferedReader(new InputStreamReader(activeReader.getInputStream()))) {
     String line;
     while((line=br.readLine())!=null && gen==generation) {
      if(line.contains("BTN_TRIGGER_HAPPY32")) {
       if(line.trim().endsWith("DOWN")) cb.event(true, SystemClock.uptimeMillis());
       else if(line.trim().endsWith("UP")) cb.event(false, SystemClock.uptimeMillis());
      }
     }
    }
    if(gen==generation) cb.status(2);
   } catch(Exception e) { try { if(gen==generation)cb.status(3); }catch(Exception ignored){} }
  },"sidekey-input").start();
 }
 public synchronized void stop() { generation++; if(reader!=null){reader.destroy(); reader=null;} callback=null; }
 public String getSystemAction() {
  try { return run("settings", "get", "system", "oplus_action_button_switch_state"); }
  catch (Exception e) { throw new IllegalStateException("Cannot read system action", e); }
 }
 public void setSystemAction(String value) {
  if (value == null || value.equals("null")) {
   try { run("settings", "delete", "system", "oplus_action_button_switch_state"); }
   catch (Exception e) { throw new IllegalStateException("Cannot restore system action", e); }
   return;
  }
  if (value.isEmpty() || value.length() > 128 || value.contains("\n")) throw new IllegalArgumentException("Invalid system action");
  try { run("settings", "put", "system", "oplus_action_button_switch_state", value); }
  catch (Exception e) { throw new IllegalStateException("Cannot set system action", e); }
 }
 public void media(int code) {
  if (code != 85 && code != 87 && code != 88 && code != 24 && code != 25) throw new IllegalArgumentException("Invalid media action");
  try { run("input", "keyevent", String.valueOf(code)); }
  catch (Exception e) { throw new IllegalStateException("Cannot perform media action", e); }
 }
 public void destroy() { stop(); System.exit(0); }
}
