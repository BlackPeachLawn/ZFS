import com.sun.deploy.trace.FileTraceListener;
import com.sun.org.apache.xerces.internal.util.ShadowedSymbolTable;
import org.omg.PortableInterceptor.SYSTEM_EXCEPTION;
import org.omg.PortableServer.THREAD_POLICY_ID;
import sun.rmi.runtime.Log;

import java.io.*;
import java.net.*;
import java.rmi.server.ServerNotActiveException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FIleServer {
    static final int SERVER_PORT_NUM=30000;
    static final int SERVER_HEART_PORT =30001;
    static final int SERVER_CLIENT_PORT=30002;
    static final String SERVER_IP="127.0.0.1";
    static final Map <String,String> registeredStNode=new HashMap<>();
    static final List<StNodeInfo> stNodeList=new ArrayList<>();
    static List<FileInfo> fileList;

    private List<Runnable> taskList;

    private FIleServer(){
        taskList =new LinkedList<>();
    }

    private void init(){
        try{
            ObjectInputStream ism=new ObjectInputStream(new FileInputStream("FileInfo.txt"));
            fileList=(List<FileInfo>)ism.readObject();
        }
        catch (IOException | ClassNotFoundException e){
            System.out.println(e.getMessage());
        } finally {
            if(fileList==null){
                fileList= new ArrayList<>();
            }
        }
    }

    public static void main(String[] args){
        try {
            FIleServer ser=new FIleServer();
            ser.init();
            ser.taskList.add(new HeartbeatListener());
            ser.taskList.add(new NodeAliveChecker());
            ser.taskList.add(new ClientListener());
            ExecutorService exec= Executors.newCachedThreadPool();
            for(Object i:ser.taskList){
                exec.execute((Runnable) i);
            }
            exec.shutdown();
        }
        catch (IOException e){
            System.out.println(e.getMessage());
        }

    }
}

class HeartbeatListener implements Runnable{
    @Override
    public void run() {
        while (true){
            byte[] buffer=new byte[1024];
            DatagramPacket datapacket=new DatagramPacket(buffer,buffer.length);
            try {
                InetAddress add=InetAddress.getByName(FIleServer.SERVER_IP);
                DatagramSocket soc=new DatagramSocket(FIleServer.SERVER_HEART_PORT,add);
                soc.receive(datapacket);
                soc.close();
                String msg=new String(buffer,0,datapacket.getLength());
                System.out.println("Receive message:\n"+msg);

                boolean flag=false;
                String[] arr=msg.split("\n");
                StNodeInfo tmp=new StNodeInfo(arr[0],arr[1],Integer.parseInt(arr[2]),Long.parseLong(arr[3]), Long.parseLong(arr[4]));
                tmp.lastVis=System.currentTimeMillis();
                for(StNodeInfo i: FIleServer.stNodeList){
                    if(i.nodeName.equals(arr[0])){
                        i.lastVis=tmp.lastVis;
                        i.volume.setAvailableBytes(tmp.volume.getAvailalblebytes());
                        flag=true;
                    }
                }
                if(!flag)
                    FIleServer.stNodeList.add(tmp);
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }
    }
}

class NodeAliveChecker implements Runnable{
    int last=-1;
    @Override
    public void run() {
        while (true) {
            long cutime = System.currentTimeMillis();
            boolean flag;
            do {
                flag = false;
                if(FIleServer.stNodeList.size()!=last){
                    System.out.printf("Active Storage Node number : %d\n", FIleServer.stNodeList.size());
                    last=FIleServer.stNodeList.size();
                }
                for (int i = 0; i != FIleServer.stNodeList.size(); i++) {
                    if (cutime - FIleServer.stNodeList.get(i).lastVis > 15000) {
                        System.out.printf("'%s' %s:%s is not active! It's removed From the file server!\n",
                                FIleServer.stNodeList.get(i).nodeName, FIleServer.stNodeList.get(i).nodeIP, FIleServer.stNodeList.get(i).nodePort);
                        FIleServer.stNodeList.remove(i);
                        flag = true;
                        break;
                    }
                }
            } while (flag);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            finally {
                Thread.yield();
            }
        }
    }
}

class ClientListener implements Runnable {
    private ServerSocket ss = new ServerSocket(FIleServer.SERVER_CLIENT_PORT);

    ClientListener() throws IOException {
    }

    @Override
    public void run() {
        outside:
        while (true){
            Socket s;InputStream is;OutputStream os;
            try {
                s=ss.accept();
                s.setSoTimeout(5000);
                is=s.getInputStream();os=s.getOutputStream();
                BufferedReader br=new BufferedReader(new InputStreamReader(is));

                String md5=br.readLine();
                String fname=br.readLine();
                String flen=br.readLine();

                BufferedWriter bw=new BufferedWriter(new OutputStreamWriter(os));
                if(FIleServer.fileList!=null){
                    for(FileInfo i:FIleServer.fileList){
                        if(i.md5.equals(md5))
                        {
                            bw.write("exist\n");
                            bw.close();br.close();
                            is.close();os.close();
                            s.close();
                            continue outside;
                        }
                    }
                }

                Collections.sort(FIleServer.stNodeList);
                if(FIleServer.stNodeList.size()>=2){
                    String mNode=FIleServer.stNodeList.get(0).nodeIP+" "+FIleServer.stNodeList.get(0).nodePort;
                    String bNode=FIleServer.stNodeList.get(1).nodeIP+" "+FIleServer.stNodeList.get(1).nodePort;
                    bw.write(mNode+"\n");
                    bw.write(bNode+"\n");

                    String uuid=UUID.randomUUID().toString();

                    System.out.println("Receiving file uploading request.");
                    System.out.printf(">>File Name: %s\n",fname);
                    System.out.printf(">>File length: %s\n",flen);
                    System.out.printf(">>MD5 of this file: %s\n",md5);
                    System.out.printf(">>UUID of this file: %s\n",uuid);
                    System.out.printf("Allocating main Storage Node %s to the client.",mNode);
                    System.out.printf("Allocating backup Storage Node %s to the client.",bNode);
                    FIleServer.fileList.add(new FileInfo(fname,uuid,Long.parseLong(flen),mNode,bNode,md5));
//                    writeFileInfo();

                    bw.write(uuid+"\n");

                    bw.close();br.close();
                    is.close();os.close();
                    s.close();
                }
                else {
                    throw new FileServerException("Not enough Storage node!");
                }
            } catch (IOException e) {
                System.out.println(e.getMessage());
            } catch (FileServerException e) {
                e.printStackTrace();
            }
        }
    }

    private void writeFileInfo() throws IOException {

            ObjectOutputStream osm =new ObjectOutputStream(new FileOutputStream("FileInfo.txt"));
            osm.writeObject(FIleServer.fileList);
            osm.flush();osm.close();
    }
}

class FileServerException extends Exception {
    public FileServerException(){}
    public FileServerException(String msg){
        super(msg);
    }
}