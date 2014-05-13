package com.example.ssh_server;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;

import org.apache.sshd.SshServer;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.PasswordAuthenticator;
import org.apache.sshd.server.PublickeyAuthenticator;
import org.apache.sshd.server.command.ScpCommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

















import android.os.Bundle;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;




public class MainActivity extends Activity {
	
	private Button start;
	private Button stop;
	private Button settings;
	private TextView addr;
	private TextView port;
	private TextView status;
	public static final String PREFS_NAME = "PrefFile";
	public static final String ready_status = "READY";
	public int saved_port;
	public String saved_passwd;
	
	private final SshServer sshd = SshServer.setUpDefaultServer();
//	private final SimpleForwardingFilter forwardingFilter = new SimpleForwardingFilter();
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		start= (Button) findViewById(R.id.button1);
		stop= (Button) findViewById(R.id.Button01);
		settings=(Button)findViewById(R.id.button2);
		stop.setEnabled(false);
		port=(TextView)findViewById(R.id.TextView02);
		status=(TextView)findViewById(R.id.TextView04);
		addr=(TextView)findViewById(R.id.textView2);
		String ipaddr=Utils.getIPAddress(true);
		Log.d("debug",ipaddr);
		addr.setText("root@"+ipaddr);
	//	Log.d("debug", "before pref");
		SharedPreferences pref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		saved_port=pref.getInt("PORT_NUM", 8022);
		saved_passwd=pref.getString("PASSWORD", "admin");
	    
		port.setText(Integer.toString(saved_port));
		
		status.setText(ready_status);
		stop.setOnClickListener( new OnClickListener(){

			@Override
			public void onClick(View v) {
				Log.d("debug","stop clicked" );
				try{
					stop_btn_clicked();
				}
				 catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		});
	
		start.setOnClickListener( new OnClickListener(){
			@Override
			public void onClick(View v) {
				Log.d("debug","start clicked" );
				try {
					start_btn_clicked();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			}
		});
		
	 settings.setOnClickListener(new OnClickListener(){

		@Override
		public void onClick(View v) {
			Intent i = new Intent("com.example.ssh_server.OPTIONS");
			startActivity(i);
			
		}
	 });
		
	}

	
	
	private void start_btn_clicked() throws IOException{
		
	Log.d("debug","handler");

	//SshServer sshd = SshServer.setUpDefaultServer();
	Log.d("debug","server defined");
	sshd.setPort(saved_port);
	Log.d("debug","port set");
	Log.d("debug",saved_passwd);
	PasswordAuthenticator auth = new PasswordAuthenticator() {
		
	     public boolean authenticate (String string, String string1, 
	    		 ServerSession ss) {
	    	 Log.d("debug",string+" "+string1+" "+saved_passwd);
	    	 if(string.equals("root") && string1.equals(saved_passwd))
	    	 {
	    		 Log.d("debug","authenticated");
	    		 return true;
	    	 }
	    	 else 
	    	 {
	    		 return false;
	    	 }
	     }

		 
	};
	Log.d("debug","password authenticator defined");
	PublickeyAuthenticator auth1 = new PublickeyAuthenticator(){
	
		@Override
		public boolean authenticate(String arg0, PublicKey arg1,
				ServerSession arg2) {
			// TODO Auto-generated method stub
			return true;
		}
	
	
	
    };
    Log.d("debug","key authenticator defined");	
    File f =getFilesDir();
  	sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(f+"/hostkey.ser"));
  
  	Log.d("debug",f+"");
  	Log.d("debug","key pair provider set");
  	
 /* 	sshd.setCommandFactory(new ScpCommandFactory(new CommandFactory() {
  	    public Command createCommand(String command) {
  	        return  new ProcessShellFactory(command.split(" ")).create();
  	    }
  	}));*/
    sshd.setShellFactory(new CustomTerminalFactory("/system/bin/sh", "-i"));
	//sshd.setShellFactory(new ProcessShellFactory(new String[] { "system/bin/sh", "-i"}));
	Log.d("debug","shell factory set");
	sshd.setPublickeyAuthenticator(auth1);
	Log.d("debug","publickey set");
	sshd.setPasswordAuthenticator(auth);
	Log.d("debug","password set");
	
	
	Log.d("debug","before start");
	
	sshd.start();
	
	Log.d("debug","after start");
	
	status.setText("RUNNIG");
	start.setEnabled(false);
	stop.setEnabled(true);
		
	
	
   }


	private void stop_btn_clicked() throws InterruptedException {
		
		sshd.stop();
		Log.d("debug","sshd stopped");
		stop.setEnabled(false);
		start.setEnabled(true);
		status.setText("STOPPED");
	}
	
	
}