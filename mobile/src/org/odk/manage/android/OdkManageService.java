package org.odk.manage.android;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import org.odk.common.android.FileHandler;
import org.odk.common.android.SharedConstants;
import org.odk.common.android.Task;
import org.odk.common.android.Task.TaskStatus;
import org.odk.common.android.Task.TaskType;
import org.odk.manage.android.comm.CommunicationProtocol;
import org.odk.manage.android.comm.HttpAdapter;
import org.odk.manage.android.worker.Worker;
import org.odk.manage.android.worker.WorkerTask;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

// TODO(alerer): some of these methods need to be synchronized
// Actually, it may be better to run all tasks synchronously with a work queue
// instead of running them concurrently - this will prevent overutilizing system 
// resources, as well as reducing the risk of concurrency bugs. Just need to 
// make sure that all tasks will timeout properly.
public class OdkManageService extends Service{

  public static final String MESSAGE_TYPE_KEY = "messagetype";
  public static enum MessageType {
    NEW_TASKS, CONNECTIVITY_CHANGE, PHONE_PROPERTIES_CHANGE, BOOT_COMPLETED, PACKAGE_ADDED;
  }
  
  public FileHandler fileHandler;
  private SharedPreferencesAdapter prefsAdapter;
  private DbAdapter dba;
  private PhonePropertiesAdapter propAdapter;
  private String imei;
  private Worker worker;
  
  // Lifecycle methods
  
  /** not using ipc... dont care about this method */
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onStart(Intent i, int startId){
    //TODO(alerer): these tasks are very coarse-grained. Think about whether more 
    //fine-grained tasks would be better (e.g. timeouts could be better suited to 
    //the particular task
    final Intent mIntent = i;
    worker.addTask(new WorkerTask(){
      @Override
      public void execute(){
        OdkManageService.this.handleIntent(mIntent);
      }
      @Override
      public long getTimeoutMillis(){ //Not implemented in Worker
        return Constants.SERVICE_OPERATION_TIMEOUT_MS;
      }
    });
  }
  
  private void handleIntent(Intent i){
    MessageType mType = (MessageType) i.getExtras().get(MESSAGE_TYPE_KEY);
    Log.i(Constants.TAG, "OdkManageService started. Type: " + mType);
    
    syncDeviceImeiRegistration();
    
    //TODO(alerer): use the CommunicationStategy
    boolean isConnected = isNetworkConnected();
    switch (mType) {
      case NEW_TASKS:
        setNewTasksPref(true);
        
        if (isConnected) {
          requestNewTasks();
          processPendingTasks();
          sendStatusUpdates();
        }
        break; 
      case CONNECTIVITY_CHANGE:
        if (isConnected) {
          if (getNewTasksPref()){
            requestNewTasks();
          }
          processPendingTasks();
          sendStatusUpdates();
        }
        break;
      case PHONE_PROPERTIES_CHANGE:
        Log.d(Constants.TAG, "Phone properties changed.");
        break;
      case PACKAGE_ADDED:
        handlePackageAddedIntent(i.getExtras().getString("packageName"));
        if (isConnected) {
          sendStatusUpdates();
        }
        break;
      default:
        Log.w(Constants.TAG, "Unexpected MessageType in OdkManageService");
    }
  }
  
  private boolean isNetworkConnected(){
    NetworkInfo ni = getNetworkInfo();
    return (ni != null && NetworkInfo.State.CONNECTED.equals(ni.getState()));
  }
  
  @Override 
  public void onCreate() {
    super.onCreate();
    Log.i(Constants.TAG, "OdkManageService created.");
    
    propAdapter = new PhonePropertiesAdapter(this);
    imei = propAdapter.getIMEI();
    
    dba = new DbAdapter(this, Constants.DB_NAME);
    dba.open();
    
    prefsAdapter = new SharedPreferencesAdapter(this);
    fileHandler = new FileHandler(this);
    
    registerPhonePropertiesChangeListener();
    
    worker = new Worker();
    worker.start();
    
  }

  @Override 
  public void onDestroy() {

    Log.i(Constants.TAG, "OdkManageService destroyed.");
    worker.stop();
    dba.close();
    dba = null;
    super.onDestroy();
  }
  
  /////////////////////////
 
  
  private void syncDeviceImeiRegistration(){
    
    DeviceRegistrationHandler drh = new DeviceRegistrationHandler(this);
    if (drh.registrationNeededForImei()){
      Log.i(Constants.TAG, "IMSI changed: Registering device");
      drh.register(CommunicationProtocol.SMS);
    }
  }
  
  
  private void handlePackageAddedIntent(String packageName){
    Log.d(Constants.TAG, "Package added detected: " + packageName);
    List<Task> pendingTasks = dba.getPendingTasks();
    for (Task t: pendingTasks) {
      Log.d(Constants.TAG, "Type: " + t.getType() + "; Name: " + t.getName());
      if (t.getType().equals(TaskType.INSTALL_PACKAGE) && 
          t.getName().equals(packageName)){ // extras stores the package name
        dba.setTaskStatus(t, TaskStatus.SUCCESS);
        Log.d(Constants.TAG, "Task " + t.getUniqueId() + " (INSTALL_PACKAGE) successful.");
        break;
      }
    }
    
  }
  private void registerPhonePropertiesChangeListener() {

    Intent mIntent = new Intent(this, OdkManageService.class);
    mIntent.putExtra(MESSAGE_TYPE_KEY, MessageType.PHONE_PROPERTIES_CHANGE);
    PendingIntent pi = PendingIntent.getService(this, 0, mIntent, 0);
    propAdapter.registerListener(pi);
  }
  
  
  private NetworkInfo getNetworkInfo() {
    ConnectivityManager cm = (ConnectivityManager) 
        getSystemService(Context.CONNECTIVITY_SERVICE);
    
    // going to print a bunch of network status info to logs
    NetworkInfo mobileNi = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
    Log.d(Constants.TAG, "Mobile status: " + mobileNi.getState().name());
    NetworkInfo wifiNi = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
    Log.d(Constants.TAG, "Wifi status: " + wifiNi.getState().name());
    
    NetworkInfo activeNi = cm.getActiveNetworkInfo();
    if (activeNi != null) {
      Log.d(Constants.TAG, "Active status: " + activeNi.getState().name());
      Log.d(Constants.TAG, "Active type: " + activeNi.getTypeName());
      return activeNi;
    } else {
      Log.d(Constants.TAG, "Active type: NONE");
      return null;
    }
  }
  
  private List<Task> getTasksFromTasklist(Document doc){
List<Task> tasks = new ArrayList<Task>();
    
    doc.getDocumentElement().normalize();
    
    NodeList taskNodes = doc.getElementsByTagName("task");
    
    Log.i(Constants.TAG,"=====\nTasks:");
    for (int i = 0; i < taskNodes.getLength(); i++) {
      if (!(taskNodes.item(i) instanceof Element)) {
        continue;
      }
      Element taskEl = (Element) taskNodes.item(i);
      Log.i(Constants.TAG,"-----");
      NamedNodeMap taskAttributes = taskEl.getAttributes();
      
      // parsing ID
      String id = getAttribute(taskAttributes, "id");
      Log.i(Constants.TAG, "Id: " + id);
      
      // parsing type
      String typeString = getAttribute(taskAttributes, "type");
      Log.i(Constants.TAG, "Type: " + typeString);
      TaskType type = null;
      try {
        type = Enum.valueOf(TaskType.class, typeString);
      } catch (Exception e) {
        Log.e(Constants.TAG, "Type not recognized: " + typeString);
        continue;
      }
      
      Task task = new Task(id, type, TaskStatus.PENDING);
      tasks.add(task);
      
      task.setName(getAttribute(taskAttributes, "name"));
      task.setUrl(getAttribute(taskAttributes, "url"));
      task.setExtras(getAttribute(taskAttributes, "extras"));
    }
    
    return tasks;
  }
  
  private String getAttribute(NamedNodeMap attributes, String name) {
    if (attributes.getNamedItem(name) == null) {
      return null;
    }
    return attributes.getNamedItem(name).getNodeValue();
  }
  
  private void requestNewTasks(){
    Log.i(Constants.TAG, "Requesting new tasks");

    // remember that we have new tasks in case we can't retrieve them immediately
    String baseUrl = prefsAdapter.getString(Constants.PREF_URL_KEY, "");
    
    // get the tasks input stream from the URL
    InputStream newTaskStream = null;
    try{
      String imei = new PhonePropertiesAdapter(this).getIMEI();
      String url = getTaskListUrl(baseUrl, imei);
      Log.i(Constants.TAG, "tasklist url: " + url);
      newTaskStream = new HttpAdapter().getUrl(url);

      if (newTaskStream == null){
        Log.e(Constants.TAG,"Null task stream");
        return;
      }
      
      Document doc = null;
      try{
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        doc = db.parse(newTaskStream);
      } catch (ParserConfigurationException e){
        Log.e(Constants.TAG,"",e);
      } catch (IOException e){
        Log.e(Constants.TAG,"",e);
      } catch (SAXException e){
        Log.e(Constants.TAG,"",e);
      }
      if (doc == null)
        return;
      
      List<Task> tasks = getTasksFromTasklist(doc);
      
      if (tasks == null){
        Log.e(Constants.TAG, "Tasklist was null");
        return;
      }
   
      int added = 0;
      for (Task t: tasks) {
        if (dba.addTask(t) > -1) {
          added++;
        }
      }
      Log.d(Constants.TAG, added + " new tasks were added.");
      setNewTasksPref(false);
    } catch (IOException e) {
      //TODO(alerer): do something here
      Log.e(Constants.TAG, "IOException downloading tasklist", e);
    } finally {
      try {
        if (newTaskStream != null) {
          newTaskStream.close();
        }
      } catch (IOException e) {
        Log.e(Constants.TAG, "IOException on closing new task stream", e);
      }
    }
  }
  
  
  private String getTaskListUrl(String baseUrl, String imei){
    if (baseUrl.charAt(baseUrl.length()-1) == '/')
      baseUrl = baseUrl.substring(0, baseUrl.length()-1);
    //isDevice=true tells the server that it is actually the device requesting
    //the tasklist - thus, this should count as a device contact (versus an 
    //administrator requesting the tasklist)
    return baseUrl + "/tasklist?isDevice=true&imei=" + imei;
  }
  
  private void processPendingTasks(){

    List<Task> tasks = dba.getPendingTasks();
    Log.d(Constants.TAG, "There are " + tasks.size() + " pending tasks.");
    
    for (Task t: tasks) {
      assert(t.getStatus().equals(TaskStatus.PENDING)); //just to check
      TaskStatus result = attemptTask(t);
      dba.setTaskStatus(t, result);
    }
  }
  
  /**
   * Send a status update message to the ODK Manage server, listing any 
   * tasks whose status has changed. These tasks are then marked as synced in 
   * the local DB.
   * 
   * @return true if the message was sent successfully, or no message was required.
   */
  private boolean sendStatusUpdates(){
    List<Task> tasks = dba.getUnsyncedTasks();
    StatusUpdateXmlGenerator updateGen = new StatusUpdateXmlGenerator(imei);
    for (Task t: tasks){
      assert(!t.isStatusSynced());
      updateGen.addTask(t);
    }
    Log.d(Constants.TAG, "Tasks with status updates: " + tasks.size());
    if (tasks.size() == 0) {
      return true;
    }
    String updateXml = updateGen.toString();
    String manageUrl = prefsAdapter.getString(Constants.PREF_URL_KEY, "");
    boolean success = 
      new HttpAdapter().doPost(manageUrl + "/" + Constants.UPDATE_PATH, updateXml);
    Log.i(Constants.TAG, "Status update message " + (success?"":"NOT ") + "successful");
    if (success) {
      for (Task t: tasks){
        dba.setTaskStatusSynced(t, true);
      }
    }
    return success;
  }
  
  private TaskStatus attemptTask(Task t){
    
    Log.i(Constants.TAG,
         "Attempting task\nType: " + t.getType() + 
         "\nURL: " + t.getUrl());
    
    switch(t.getType()) {
      case ADD_FORM:
        return attemptAddForm(t);
      case INSTALL_PACKAGE:
        return attemptInstallPackage(t);
      default:
        Log.w(Constants.TAG, "Unrecognized task type");
        return TaskStatus.FAILED;
    }
  }
  
  //TODO(alerer): change this to firing off an intent to ODK Collect
  private TaskStatus attemptAddForm(Task t){
    assert(t.getType().equals(TaskType.ADD_FORM));
    
    FileHandler fh = new FileHandler(this);
    File formsDirectory = null;
    try { 
      formsDirectory = fh.getDirectory(SharedConstants.FORMS_PATH);
    } catch (IOException e){
      Log.e("OdkManage", "IOException getting forms directory");
      return TaskStatus.PENDING;
    }
    
    String url = t.getUrl();
    try{
      boolean success = fh.getFormFromUrl(new URL(url), 
          formsDirectory) != null;
      Log.i(Constants.TAG, 
          "Downloading form was " + (success? "":"not ") + "successfull.");
      return success ? TaskStatus.SUCCESS : TaskStatus.PENDING;
    } catch (IOException e){
      Log.e(Constants.TAG, 
          "IOException downloading form: " + url, e);
      return TaskStatus.PENDING;
    }
  }
  
  //TODO(alerer): who should handle this? Probably Manage...just make sure.
  private TaskStatus attemptInstallPackage(Task t){
    assert(t.getType().equals(TaskType.INSTALL_PACKAGE));
    
    FileHandler fh = new FileHandler(this);
    File packagesDirectory = null;
    try { 
      packagesDirectory = fh.getDirectory(Constants.PACKAGES_PATH);
    } catch (IOException e){
      Log.e("OdkManage", "IOException getting packages directory");
      return TaskStatus.PENDING;
    }
    
    String url = t.getUrl();
    try { 
      File apk = fh.getFileFromUrl(new URL(url), packagesDirectory);
//      try {   
//        //Note: this will only work in /system/apps
//        Intent installIntent = new Intent(Intent.ACTION_PACKAGE_INSTALL,
//             Uri.parse("file://" + apk.getAbsolutePath().toString()));
//        context.startActivity(installIntent);
//        } catch (Exception e) {
//          Log.e(Constants.TAG, 
//              "Exception when doing auto-install package", e);
//        }
        try { 
          Uri uri = Uri.parse("file://" + apk.getAbsolutePath().toString());
//          PackageInstaller.installPackage(ctx, uri);
//          
          Intent installIntent2 = new Intent(Intent.ACTION_VIEW);
          installIntent2.setDataAndType(uri,
              "application/vnd.android.package-archive");
          installIntent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          startActivity(installIntent2);
          if (t.getName() == null) {
            return TaskStatus.SUCCESS; // since we don't know the package name, we have to just assume it worked.
          } else {
            return TaskStatus.PENDING; // wait for a PACKAGE_ADDED intent for this package name
          }
        } catch (Exception e) {
          Log.e(Constants.TAG, 
              "Exception when doing manual-install package", e);
          return TaskStatus.PENDING;
        }
    } catch (IOException e) {
      Log.e(Constants.TAG, 
          "IOException getting apk file: " + url);
      return TaskStatus.PENDING;
    }
  }
  
  private boolean getNewTasksPref(){
    return prefsAdapter.getPreferences().getBoolean(Constants.PREF_NEW_TASKS_KEY, false);
  }
  private void setNewTasksPref(boolean newValue){
    SharedPreferences.Editor editor = prefsAdapter.getPreferences().edit();
    editor.putBoolean(Constants.PREF_NEW_TASKS_KEY, newValue);
    editor.commit();
  }

}
