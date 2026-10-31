package cc.hyoban.moments.sidekey;
import cc.hyoban.moments.sidekey.IKeyCallback;
interface IKeyBridge {
 void start(IKeyCallback callback) = 0;
 void stop() = 1;
 String getSystemAction() = 2;
 void setSystemAction(String value) = 3;
 void media(int code) = 4;
 void destroy() = 16777114;
}
