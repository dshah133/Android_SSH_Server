package com.example.ssh_server;

import android.os.Bundle;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

public class Options extends Activity {

	private EditText passwd;
	private EditText port;
	private int saved_port;
	private Button save;
	private Button back;
	private String saved_passwd;
	
	public static final String PREFS_NAME = "PrefFile";
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_options);
		passwd=(EditText)findViewById(R.id.editText1);
		port=(EditText)findViewById(R.id.editText2);
		save=(Button)findViewById(R.id.button1);
		back=(Button)findViewById(R.id.button2);
		save.setEnabled(false);
		
		SharedPreferences pref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

		saved_port=pref.getInt("PORT_NUM", 8022);
		
		port.setText(Integer.toString(saved_port));

		saved_passwd=pref.getString("PASSWORD", "admin");

		passwd.setText(saved_passwd);

		save.setOnClickListener(new OnClickListener(){

			@Override
			public void onClick(View v) {
				save_user_data();
				
			}
		});
		
		back.setOnClickListener(new OnClickListener(){

			@Override
			public void onClick(View v) {
				   Intent intent = new Intent(Options.this, MainActivity.class);
				   intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);   
				   startActivity(intent);
			}
			
			
			
		});
		passwd.addTextChangedListener(new TextWatcher(){

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void afterTextChanged(Editable s) {
				
				save.setEnabled(true);
			}
			
			
			
		});
		
	  port.addTextChangedListener(new TextWatcher(){

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count,
				int after) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before,
				int count) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void afterTextChanged(Editable s) {
			if(!save.isEnabled()){
			save.setEnabled(true);	
				
			}
			
		}
		  
		  
		  
		  
	  });	
	
	}
	
	
	
	private void save_user_data()
	{
		
		saved_passwd=passwd.getText().toString();
		saved_port=Integer.parseInt(port.getText().toString());
		Log.d("debug",saved_port+"");
		Log.d("debug",saved_passwd);
		SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
	    SharedPreferences.Editor editor = settings.edit();
	    editor.putInt("PORT_NUM", saved_port);
	    editor.putString("PASSWORD", saved_passwd);

		editor.commit();
		Log.d("debug","data saved");
		save.setEnabled(false);
		
	}

	

}
