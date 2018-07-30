package com.museui.muse.artuedu.museui;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import com.choosemuse.libmuse.Accelerometer;
import com.choosemuse.libmuse.AnnotationData;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.MessageType;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConfiguration;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseFileFactory;
import com.choosemuse.libmuse.MuseFileReader;
import com.choosemuse.libmuse.MuseFileWriter;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.choosemuse.libmuse.MuseVersion;
import com.choosemuse.libmuse.Result;
import com.choosemuse.libmuse.ResultLevel;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MuseActivity extends AppCompatActivity implements View.OnClickListener{

    private final String TAG = "TestLibMuseAndroid";

    /**
     * MuseManager sera la forma de comunicacion para tetectar nuevas headbands y recibir datos
     * cuando la lista de headbands diponibles cambie.
     */
    private MuseManagerAndroid manager;

    /**
     * Muse se refiere a Muse headband.  Es necesario para conectar y desconectar de la headband,
     * registrar listeners para recibir datos EEG  y obtener informacion de configuracion y version
     * de la headband.
     */
    private Muse muse;

    /**
     * ConnectionListener sera notificado cuando exista un cambio en el estado de conexion de la
     * headband, por ejemplo cuando la headband se conecta o desconecta.
     * ConnectionListener es una clase interna dentro de este mismo archivo que hereda de
     * MuseConnectionListener.
     */
    private ConnectionListener connectionListener;

    /**
     * DataListener sera la forma de recibir EEG datos (y otros) de la headband.
     * DataListener es una clase definida en este mismo archivo que hereda de MuseDataListener.
     */
    private DataListener dataListener;

    /**
     * Los datos son recibidos en una alta velocidad; 220Hz, 256Hz o 500Hz, dependiendo del tipo
     * de headband y la configuracion de la misma.  Los datos recibidos son almacenados en el
     * buffer hasta que se actualiza la IU.
     *
     * Las banderas indican si algun dato nuevo ha sido recibido y los buffers gurdan los valores
     * del ultimo paquete recivido.
     * Para este ejemplo, los valores de EEG, ALPHA_RELATIVE y ACCELEROMETER son mostrados.
     *
     * Nota: el tamaño de los arreglos del buffer son tomados dependiendo de los paquetes.
     * MuseDataPacketType, consta de 3 valores para accelerometer y 6 para EEG y EEG-derived packets.
     */
    private final double[] eegBuffer = new double[6];
    private boolean eegStale;
    private final double[] alphaBuffer = new double[6];
    private boolean alphaStale;
    private final double[] accelBuffer = new double[3];
    private boolean accelStale;

    /**
     * Los valores presentados en la IU seran actualizados cada 60fps por medio de un Handler.
     * ya que los paquetes transmiten en una frecuancia alta y no es necesario una actualizacion
     * tan constante. Los metodos de actualizacion realizan una asignacion de cadena por lo que
     * gastan cierta memoria y hace mas conveniente el no actualizarlos tan en periodos cortos.
     */
    private final Handler handler = new Handler();

    /**
     * La lista de dispositivos disponibles para establecer conexion sera mostrada en la IU
     * mediante un spinner el cual contendra las direcciones MAC de todas las headbands encontradas.
     */
    private ArrayAdapter<String> spinnerAdapter;

    /**
     * Es posible detener la transmision de datos de la headband. Este boolean controlara si la
     * transmision de datos esta activa o no, lo que permitira al usuario controlar la transmision
     * de datos.
     */
    private boolean dataTransmission = true;

    /**
     * Para guardar los datos en un archivo, es necesario usar un MuseFileWriter. El MuseFileWriter
     * convierte los datos recibidos en un formato binario compacto.
     * Para leer los datos guardados, es necesario utilizar un MuseFileReader.
     */
    private final AtomicReference<MuseFileWriter> fileWriter = new AtomicReference<>();

    /**
     * Para que las opereaciones de archivo no disminuyan la velocidad de la IU, seran ejecutadas
     * por un handler en un hilo separado.
     */
    private final AtomicReference<Handler> fileHandler = new AtomicReference<>();

    /**
     * Con el objetivo de un mejor diseño de la interfaz para el usuario, la siguiente badera controlara si el dispositivo esta
     * conectado, se usara el mismo boton para conectar que para desconectar.
     */

    private boolean connectionStatus = false;

    //--------------------------------------
    // Ciclo de vida / Código de conexión

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_muse);

        // Es necesario establecer un contexto en MuseManagerAndroid antes de cualquier cosa.
        // Proveniente de otra LibMuse API invocada tambien en la libreria.
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);
        //manager.setMuseListener(this);

        Log.i(TAG, "LibMuse version=" + LibmuseVersion.instance().getString());

        WeakReference<MuseActivity> weakActivity =
                new WeakReference<MuseActivity>(this);
        // Registro de un nuevo listener para recibir cambios en el estado de conexion.
        connectionListener = new ConnectionListener(weakActivity);
        // Registro de un listener para recivir datos de un dispositivo(Muse).
        dataListener = new DataListener(weakActivity);
        // Registro de un listener para recibir notificaciones de las headbands disponibles para conectar.
        manager.setMuseListener(new MuseL(weakActivity));

        // Muse 2016 (MU-02) headbands usan  tecnologia Bluetooth Low Energy para simplificar el
        // proceso de conexion. Esta tecnologia requiere acceso a COARSE_LOCATION o FINE_LOCATION
        // permissions.  Es necesario conocer si se tienen estos permisos antes de proceder.
        ensurePermissions();

        // Carga e inicializacion de la IU.
        initUI();

        // Inicio de un hilo para las operaciones de archivo asincronas.
        // En caso que se quiera guardar o leer información(I/O).
        fileThread.start();

        // Inicio de las actualizaciones asincronas de la IU.
        handler.post(tickUi);
    }

    protected void onPause() {
        super.onPause();
        // Para evitar la fuga de informacion de la libreria LibMuse es importante llamar
        // stopListening cuando la actividad es pausada.
        manager.stopListening();
    }

    public boolean isBluetoothEnabled() {
        return BluetoothAdapter.getDefaultAdapter().isEnabled();
    }

    public void onClick(View v) {
        if (v.getId() == R.id.btn_actualizar) {
            // El usuario ha presionado el boton Actualizar
            // Comienza la busqueda de Muse headbands. Primero se llama stopListening para
            // asegurarnos que startListening estara limpio y se actualizara con las headbands
            // nuevas detectadas.
            manager.stopListening();
            manager.startListening();

        } else if (v.getId() == R.id.btn_conectar_desconectar) {

            if(connectionStatus == false){

                // El usuario ha presionado el boton Conectar para conectar a la headband seleccionada
                // en el Spinner.

                // Listening es una operacion de coso alto, por lo que una vez que se conoce la headband
                // a la que se va a conectar se detiene de buscar otra headband.
                manager.stopListening();

                List<Muse> availableMuses = manager.getMuses();
                Spinner musesSpinner = (Spinner) findViewById(R.id.spinner);

                // Se busca si realmente existe algo a que conectarse.
                if (availableMuses.size() < 1 || musesSpinner.getAdapter().getCount() < 1) {
                    Log.w(TAG, "No existe dispositivo a que conectar");
                } else {

                    // Se almacena la headband(Muse) que el usuario selecciono.
                    muse = availableMuses.get(musesSpinner.getSelectedItemPosition());
                    // Se desregistran todos los listeners anteriores y se registra un data listener
                    // para recibir los datos del paquete MuseDataPacketTypes en el que se esta
                    // interesado. Si no se registra un listener para cada tipo de datos particular,
                    // no se recibira ningun pauqete de datos de ese tipo.
                    muse.unregisterAllListeners();
                    muse.registerConnectionListener(connectionListener);
                    muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
                    muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
                    muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
                    muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
                    muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
                    muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);

                    // Se inicia la conexion con la headband y se recibe datos asincronicamente.
                    muse.runAsynchronously();

                    //Cambio en el estado de la conexión
                    connectionStatus = true;
                }
            }else{
                // El ususario ha presionado el boton Desconectar.
                // Descnexion de la headband(Muse) seleccionada.
                if (muse != null) {
                    muse.disconnect();
                }
            }

        } else if (v.getId() == R.id.btn_detener_reanudar) {

            // El usuario ha presionado el oton Detener/Reanudar para detener o reanudar la
            // transmision de datos. Se intercala el estado y se pausa o reanuda la transmision de
            // datos de la headband.
            if (muse != null) {
                dataTransmission = !dataTransmission;
                muse.enableDataTransmission(dataTransmission);
            }
        }
    }

    //--------------------------------------
    // Permisos

    /**
     * El permiso ACCESS_COARSE_LOCATION es necesario para usar la libreria Bluetooth Low Energy
     * y debe ser requerida en ejecucion a Android 6.0+
     * En un dispositivo Android 6.0, el codigo siguiente mostrara 2 dialogos,
     * uno para proporcionar el contexto y el segundo para solicitar permisos.
     * En un dispositivo con una version anterior nada sera mostrado, pues el permiso es concedido
     * por el manifiesto.
     *
     * Si el permiso no es concedido, las headbands Muse 2016 (MU-02) no seran encontradas y se
     * lanzara una excepcion.
     */
    private void ensurePermissions() {

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            // No se tiene el permiso  ACCESS_COARSE_LOCATION por lo que se crea un dialogo para
            // pedir al usuario que conceda el permiso.

            DialogInterface.OnClickListener buttonListener =
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which){
                            dialog.dismiss();
                            ActivityCompat.requestPermissions(MuseActivity.this,
                                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                    0);
                        }
                    };

            // Este es el dialogo que se mostrara al usuario, el cual explica la razon por la que se
            // esta solicitando la concesion del permiso. Este sera mostrado esperando que el
            // usuario conceda el permiso.
            AlertDialog introDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.titulo_permiso)
                    .setMessage(R.string.descripcion_permiso)
                    .setPositiveButton(R.string.btn_Aceptar_permiso, buttonListener)
                    .create();
            introDialog.show();
        }
    }

    //--------------------------------------
    // Listeners

    /**
     * Este metodo recibira una llamada cada vez que se encuentre una headband.
     * Se actualiza el spinner con la direccion MAC de la nueva headband encontrada.
     */
    public void museListChanged() {
        final List<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName() + " - " + m.getMacAddress());
        }
    }

    /**
     * Este metodo recibira una llamada cada vez que cambie el estado de conexion de una de las
     * headbands y cambia el status en la IU.
     * @param p     Paquete que contiene el estado de conexion anterior y actual.
     * @param muse  La headband que cambio su estado.
     */
    public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {

        final ConnectionState current = p.getCurrentConnectionState();

        // Formato de mensaje para mostrar el cambio en el estado de conexion en la IU.
        final String status = p.getPreviousConnectionState() + " -> " + current;
        Log.i(TAG, status);

        // Actualizacion de la IU con el cambio en el estado de conexion.
        handler.post(new Runnable() {
            @Override
            public void run() {

                final TextView statusText = (TextView) findViewById(R.id.txtv_status);
                statusText.setText(status);

                //En caso dde mostrar la version al usuario
                //final MuseVersion museVersion = muse.getMuseVersion();
                //final TextView museVersionText = (TextView) findViewById(R.id.textv_version);


                // Si aun no se ha conectado a una headband, la informacion de version sera nula.
                // Se debe establecer conexion antes de que la informacion de version y
                // configuracion sea conocida.
                /*if (museVersion != null) {
                    final String version = museVersion.getFirmwareType() + " - "
                            + museVersion.getFirmwareVersion() + " - "
                            + museVersion.getProtocolVersion();
                    museVersionText.setText(version);
                } else {
                    museVersionText.setText(R.string.desconocido);
                }*/
            }
        });

        if (current == ConnectionState.DISCONNECTED) {
            Log.i(TAG, "Muse desconectado:" + muse.getName());
            // Se guarda el archivo de datos cuando la transmision se ha detenido.
            saveFile();
            // Se ha desconectado de la headband, asi que inicializa la copia en cache en null.
            this.muse = null;
        }
    }

    /**
     * Este metodo recibira una llamada cada vez que la headband mande un MuseDataPacket
     * que se tenga registrado. Se pueden utilizar diferentes listener para cada tipo de paquete o
     * uno solo para todos como se ha hecho.
     * @param p     El paquete de datos con datos provenientes de la headband (eg. EEG data)
     * @param muse  La headband que envia la informacion.
     */
    public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
        writeDataPacketToFile(p);

        // valuesSize recupera el numero de datos contenidos en el paquete.
        final long n = p.valuesSize();
        switch (p.packetType()) {
            case EEG:
                assert(eegBuffer.length >= n);
                getEegChannelValues(eegBuffer,p);
                eegStale = true;
                break;
            case ACCELEROMETER:
                assert(accelBuffer.length >= n);
                getAccelValues(p);
                accelStale = true;
                break;
            case ALPHA_RELATIVE:
                assert(alphaBuffer.length >= n);
                getEegChannelValues(alphaBuffer,p);
                alphaStale = true;
                break;
            case BATTERY:
            case DRL_REF:
            case QUANTIZATION:
            default:
                break;
        }
    }

    /**
     * Este metodo recibira una llamada cada vez que un artifact packet es generado en caso de tener
     * registrado para el tipo de datos ARTIFACTS.  MuseArtifactPackets son generados cuando un
     * parpadeo es detectado, la mandibula esta apretada y cuando la headband se pone o quita.
     * @param p     El paquete artifact con datos de la headband.
     * @param muse  La headband que envia la informacion.
     */
    public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
    }

    /**
     * Metodos para obtener diferentes tipos de paquetes de datos. Estos metodos solo almacenan los
     * datos en los buffers para mostralos en la IU posteriormente.
     *
     * getEegChannelValue puede ser usado para cualquier EEG o EEG paquete de datos derivado como
     * EEG, ALPHA_ABSOLUTE, ALPHA_RELATIVE o HSI_PRECISION. Para mas informacion revisar la
     * documentacion de MuseDataPacketType para todos los valores disponibles.
     * Paquetes de datos especificos como ACCELEROMETER, GYRO, BATTERY y DRL_REF tienen sus propios
     * metodos getter (getValue methods).
     */
    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    }

    private void getAccelValues(MuseDataPacket p) {
        accelBuffer[0] = p.getAccelerometerValue(Accelerometer.X);
        accelBuffer[1] = p.getAccelerometerValue(Accelerometer.Y);
        accelBuffer[2] = p.getAccelerometerValue(Accelerometer.Z);
    }

    //--------------------------------------
    // Metodos especificos de IU

    /**
     * Se inicializa la IU de la aplicacion.
     */
    private void initUI() {
        setContentView(R.layout.activity_muse);
        ImageButton refreshButton = (ImageButton) findViewById(R.id.btn_actualizar);
        refreshButton.setOnClickListener(this);
        ImageButton connectButton = (ImageButton) findViewById(R.id.btn_conectar_desconectar);
        connectButton.setOnClickListener(this);
        //Button disconnectButton = (Button) findViewById(R.id.btn_desconectar);
        //disconnectButton.setOnClickListener(this);
        Button pauseButton = (Button) findViewById(R.id.btn_detener_reanudar);
        pauseButton.setOnClickListener(this);

        spinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = (Spinner) findViewById(R.id.spinner);
        musesSpinner.setAdapter(spinnerAdapter);
    }

    /**
     * Ejecutable usado para actualizar la IU en 60Hz.
     *
     * Se actualiza la IU desde este ejecutable ya que se quiere actualizar en 60fps. Las funciones
     * de actualizacion realizan asignacion de strings lo que reduce la memoria de impresion.
     */
    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                updateEeg();
            }
            if (accelStale) {
                updateAccel();
            }
            if (alphaStale) {
                updateAlpha();
            }
            handler.postDelayed(tickUi, 1000 / 60);
        }
    };

    /**
     * Los siguientes metodos actualizan los TextViews en la IU con los datos provenientes de
     * los buffers.
     */
    private void updateAccel() {
        TextView acc_x = (TextView)findViewById(R.id.acc_x);
        TextView acc_y = (TextView)findViewById(R.id.acc_y);
        TextView acc_z = (TextView)findViewById(R.id.acc_z);
        acc_x.setText(String.format("%6.2f", accelBuffer[0]));
        acc_y.setText(String.format("%6.2f", accelBuffer[1]));
        acc_z.setText(String.format("%6.2f", accelBuffer[2]));
    }

    private void updateEeg() {
        /*
        TextView tp9 = (TextView)findViewById(R.id.eeg_tp9);
        TextView fp1 = (TextView)findViewById(R.id.eeg_af7);
        TextView fp2 = (TextView)findViewById(R.id.eeg_af8);
        TextView tp10 = (TextView)findViewById(R.id.eeg_tp10);
        tp9.setText(String.format("%6.2f", eegBuffer[0]));
        fp1.setText(String.format("%6.2f", eegBuffer[1]));
        fp2.setText(String.format("%6.2f", eegBuffer[2]));
        tp10.setText(String.format("%6.2f", eegBuffer[3]));
        */
    }

    private void updateAlpha() {
        /*
        TextView elem1 = (TextView)findViewById(R.id.elem1);
        elem1.setText(String.format("%6.2f", alphaBuffer[0]));
        TextView elem2 = (TextView)findViewById(R.id.elem2);
        elem2.setText(String.format("%6.2f", alphaBuffer[1]));
        TextView elem3 = (TextView)findViewById(R.id.elem3);
        elem3.setText(String.format("%6.2f", alphaBuffer[2]));
        TextView elem4 = (TextView)findViewById(R.id.elem4);
        elem4.setText(String.format("%6.2f", alphaBuffer[3]));
        */
    }

    //--------------------------------------
    // Archivos de Entrada/Salida  ( Files I/O )

    /**
     * Para evitar el bloqueo del hilo da la IU mientras se escribe un archivo, el archivo de
     * escritura se moeve a un hilo separado.
     */
    private final Thread fileThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            fileHandler.set(new Handler());
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            final File file = new File(dir, "new_muse_file.muse" );
            // MuseFileWriter se adjunta a un archivo existente.
            // Para este caso, se quiere empezar desde el inicio asi que si ya existe un archi...
            if (file.exists()) {
                file.delete();
            }
            Log.i(TAG, "Writing data to: " + file.getAbsolutePath());
            fileWriter.set(MuseFileFactory.getMuseFileWriter(file));
            Looper.loop();
        }
    };

    /**
     * Se escribe el MuseDataPacket para el archivo.  MuseFileWriter sabe como escribir todos los
     * tipos de datos generados por LibMuse.
     * @param p     El paquete de datos a escribir.
     */
    private void writeDataPacketToFile(final MuseDataPacket p) {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    fileWriter.get().addDataPacket(0, p);
                }
            });
        }
    }

    /**
     * Se hace un flush a todos los datos y se cierra el file writer.
     */
    private void saveFile() {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override public void run() {
                    MuseFileWriter w = fileWriter.get();
                    // Se pueden agregar string annotations al archivo para
                    // dar un contexto de lo que esta sucediendo en ese punto
                    // de tiempo. Una annotation puede ser una cadena arbitriaria o
                    // puede incluir una annotationdata adicional.
                    w.addAnnotationString(0, "Disconnected");
                    w.flush();
                    w.close();
                }
            });
        }
    }

    /**
     * Lee el archivo .muse proporcionado e imprime los datos en el logcat.
     * @param name  El nombre del achivo a leer.  Se asume que el archivo esta en
     *               el directorio Environment.DIRECTORY_DOWNLOADS.
     */
    private void playMuseFile(String name) {

        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, name);

        final String tag = "Muse File Reader";

        if (!file.exists()) {
            Log.w(tag, "file doesn't exist");
            return;
        }

        MuseFileReader fileReader = MuseFileFactory.getMuseFileReader(file);

        // Ciclo a traves de cada mensaje en el archivo.  gotoNextMessage leera el siguiente mensaje
        // y retornara el resultado de la operacion de lectura.
        Result res = fileReader.gotoNextMessage();
        while (res.getLevel() == ResultLevel.R_INFO && !res.getInfo().contains("EOF")) {

            MessageType type = fileReader.getMessageType();
            int id = fileReader.getMessageId();
            long timestamp = fileReader.getMessageTimestamp();

            Log.i(tag, "type: " + type.toString() +
                    " id: " + Integer.toString(id) +
                    " timestamp: " + String.valueOf(timestamp));

            switch(type) {
                // Los mensajes EEG contienen datos EEG sin procesar o datos DRL/REF.
                // Los paquetes derivados EEG como ALPHA_RELATIVE y artifact packets
                // son almacenados como mensajes MUSE_ELEMENTS.
                case EEG:
                case BATTERY:
                case ACCELEROMETER:
                case QUANTIZATION:
                case GYRO:
                case MUSE_ELEMENTS:
                    MuseDataPacket packet = fileReader.getDataPacket();
                    Log.i(tag, "data packet: " + packet.packetType().toString());
                    break;
                case VERSION:
                    MuseVersion version = fileReader.getVersion();
                    Log.i(tag, "version" + version.getFirmwareType());
                    break;
                case CONFIGURATION:
                    MuseConfiguration config = fileReader.getConfiguration();
                    Log.i(tag, "config" + config.getBluetoothMac());
                    break;
                case ANNOTATION:
                    AnnotationData annotation = fileReader.getAnnotation();
                    Log.i(tag, "annotation" + annotation.getData());
                    break;
                default:
                    break;
            }

            // Lectura del siguiente mensaje.
            res = fileReader.gotoNextMessage();
        }
    }

    //--------------------------------------
    // Traductores para Listener
    //
    // Cada una de estas clase hereda de su apropiado listener y contiene una referencia debil a la
    // actividad . Cada clase  simplemente pasa los mensajes recibidos a la Actividad.
    class MuseL extends MuseListener {
        final WeakReference<MuseActivity> activityRef;

        MuseL(final WeakReference<MuseActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }

    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MuseActivity> activityRef;

        ConnectionListener(final WeakReference<MuseActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p, muse);
        }
    }

    class DataListener extends MuseDataListener {
        final WeakReference<MuseActivity> activityRef;

        DataListener(final WeakReference<MuseActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p, muse);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            activityRef.get().receiveMuseArtifactPacket(p, muse);
        }
    }

}
