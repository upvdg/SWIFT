
/*
 * YOLO parameter
 */
// store your YOLO model in your QuPath project, in a subfolder names "models"
def MODEL_NAME = "best.pt" // "yolo11n.pt" , "sam2.1_b.pt"
def DO_YOLO_DETECTION = false

/*
 * SAM Parameter
 */
def DO_SAM_SEGMENTATION = true
def REMOVE_SAM_SEGMENTATION = DO_SAM_SEGMENTATION
def SAM_DOWNSAMPLE_FACTOR = 1
def SAM_TYPE = SAMType.VIT_L // VIT_L , SAM2_L
def SAM_OUTPUT = SAMOutput.MULTI_SMALLEST // MULTI_BEST_QUALITY , MULTI_SMALLEST,MULTI_LARGEST

/*
 * OMERO parameter
 */
def DO_PUSH_ANNOTATIONS_ON_OMERO = false 
def DELETE_ROI = false // if you want to delete ROIs on OMERO
def ROIS_OWNER = "" // use your gaspar, OR to get rois from all owners, you can set the owner to empty string, or use Utils.ALL_USERS
def SHOW_NOTIF = false

// Global variables
def DO_CURRENT_IMAGE = false
def TEST_MODE = false
VERBOSE = true
SLEEP_TIME = 1000 // ms (Because we need the viewer (SAM) we need to add some "wait" here and there )
def EXTENSION = ".ome.tiff"

/*
 * To save png and to sue SAM we need to update the currenr 
 */
def project = getProject()
def project_dir =  getProjectBaseDirectory()
def models_dir = new File(project_dir , "models")
MODEL = new File(models_dir , MODEL_NAME)
images_list = project.getImageList()

// replace the project imagelist with just the current image
if (DO_CURRENT_IMAGE) {
   def current_image_name = getCurrentServer().getMetadata().name
   images_list = images_list.findAll{ it.getImageName() == current_image_name }
}

images_list.each{
    // Set the image in the viewer
    if (VERBOSE) println "Opening image : " + it 
    Platform.runLater(() -> getCurrentViewer().setImageData( it.readImageData() ))
    Thread.sleep(SLEEP_TIME) 

    def currentHierarchy = getCurrentViewer().getHierarchy()
    if (DO_YOLO_DETECTION) {// make sure to clear objects, otherwise we keep them for SAM
    // Because clearAllObjects() doesn't work anymore
        currentHierarchy.clearAll() 
        Thread.sleep(SLEEP_TIME)     
    // we don't want to clear Yolo objects if we do not run the yolo detection
    }else {
        if (REMOVE_SAM_SEGMENTATION){// but if we still wan to remove some SAM objects
            sam_annotations = currentHierarchy.getAnnotationObjects().findAll{ it.getPathClass() == getPathClass("SAM-detection")}
            currentHierarchy.removeObjects( sam_annotations, false ) // we don't want to clear Yolo objects if we do not run the yolo detection
            Thread.sleep(SLEEP_TIME)  
        }
    }

    /*
     * Get some image information
     */
    def image_name = getProjectEntry().getImageName().split(EXTENSION)[0]

    /*
     * Prepare folders
     */
    
    def yolo_input_dir = new File(project_dir , "yolo_input")
    yolo_input_dir.mkdir()
    def yolo_input_path = new File(yolo_input_dir , image_name+".png")
    
    def yolo_output_dir = new File(project_dir , "yolo_output")
    yolo_output_dir.deleteDir()
    def yolo_ouput_path = new File(yolo_output_dir.toString() , "predict/labels/"+image_name+".txt")
    
    def yolo_boxes = []
    
    if (DO_YOLO_DETECTION){
        /*
         * SAVE current image      
         */     
        if (VERBOSE) println "Writing image : start" 
        
        def viewer = getCurrentViewer()
        writeRenderedImage(viewer, yolo_input_path.toString() )
        
        if (VERBOSE) println "Writing image : done" 
        
        /*
         * Yolo
         */ 
        def arguments = [ "task=detect" ,"mode=predict" , "model="+MODEL , "source="+yolo_input_path.toString() ,"save_txt=True", "project="+yolo_output_dir.toString(),"max_det=500" ]
        if (VERBOSE) println "Yolo detection : start" 
        runYolo(arguments)
        if (VERBOSE) println "Yolo detection: done" 
        
        yolo_boxes = loadYoloBoxes(yolo_ouput_path)   
        if (yolo_boxes!=null) currentHierarchy.addObjects( yolo_boxes )
        
        // OMERO part
        if (DO_PUSH_ANNOTATIONS_ON_OMERO){
            if (VERBOSE) println "push ROI on OMERO: start" 
            sendAnnotationsToOMERO( it,  yolo_boxes , DELETE_ROI , ROIS_OWNER , SHOW_NOTIF)
            if (VERBOSE) println "push ROI on OMERO: done" 
        }
    }
    
    /* 
     * RUN SAM on Yolo boxes 
     */
    if (DO_SAM_SEGMENTATION) {
        
        if (!DO_YOLO_DETECTION) {
            yolo_boxes = getCurrentViewer().getHierarchy().getAnnotationObjects()
            if (yolo_boxes == null) {
                println "Error : no YOLO boxes found in the image"
                return
            }
        }
        
        if (TEST_MODE) { yolo_boxes = yolo_boxes.subList(0,10) }
        
        def viewer = getCurrentViewer()
        
        // Define the zoom 
        Platform.runLater(() -> {
                logger.info("Setting viewer magnification for SAM")
                viewer.setDownsampleFactor(SAM_DOWNSAMPLE_FACTOR)
        
        })
        
        // Iterate through the yolo_boxes and start SAM one after another
        sam_annotations = yolo_boxes.collect{ yolo_box ->
            
            // center the view on the current box
            Platform.runLater(() -> {
                viewer.centerROI( yolo_box.getROI())   
            })
            sleep(100)// 50 was not enough
           
            def task = SAMDetectionTask.builder(getCurrentViewer())
                                .serverURL("http://localhost:8000/sam/")
                                .addForegroundPrompts([yolo_box])
                                .addBackgroundPrompts(Collections.emptyList())
                                .model( SAM_TYPE ) // VIT_L , SAM2_L
                                .outputType(SAM_OUTPUT) // MULTI_BEST_QUALITY , MULTI_SMALLEST
                                .setName(true)
                                .setRandomColor(true)
                                .build();
                               
             Platform.runLater(task);
             sam_annotation = task.get();
             
             return sam_annotation[0] 
            
        }
        
        //sam_annotations = sam_annotations.collect{PathObjects.createAnnotationObject( it.getROI() , getPathClass("SAM-detection")) }
        sam_annotations.each{ it.setPathClass( getPathClass("SAM-detection")) }
        currentHierarchy.addObjects(sam_annotations)

        // OMERO part
        if (DO_PUSH_ANNOTATIONS_ON_OMERO){
            annotationsToOMERO = sam_annotations
            if (DELETE_ROI) annotationsToOMERO = yolo_boxes+sam_annotations
            if (VERBOSE) println "push ROI on OMERO: start" 
            sendAnnotationsToOMERO( it, annotationsToOMERO , DELETE_ROI , ROIS_OWNER , SHOW_NOTIF)
            if (VERBOSE) println "push ROI on OMERO: done" 
        }
     }

    /* 
     * MEASURE detections
     */
    selectAnnotations()
    addShapeMeasurements("AREA", "LENGTH", "CIRCULARITY", "SOLIDITY", "MAX_DIAMETER", "MIN_DIAMETER")
    //runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"downsample":1.0,"region":"ROI","tileSizePixels":200.0,"channel1":true,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":false,"haralickMin":500.0,"haralickMax":5000.0,"haralickDistance":1,"haralickBins":32}')
    
   
   /* 
    *  Save the current state of the viewer
    */
   it.saveImageData( getCurrentViewer().getImageData() )
   Thread.sleep(SLEEP_TIME)  
}

println "Done!"

import qupath.lib.roi.RectangleROI
import qupath.lib.objects.PathObjects


/*
 * HELPERS 
 */


def sendAnnotationsToOMERO( imageEntry , pathObjects , deleteROI , roisOwner , showNotif) {
        // get the current displayed image on QuPath
    //def server = getCurrentServer()
    def server = imageEntry.readImageData().getServer()
    
    // check if the current server is an OMERO server. If not, throw an error
    if(!(server instanceof OmeroRawImageServer)){
    	Dialogs.showErrorMessage("Sending ROIs","Your image is not from OMERO ; please use an image that comes from OMERO to use this script");
    	return
    }

 
    def wasSent = OmeroRawScripting.sendPathObjectsToOmero(server, pathObjects, deleteROI, roisOwner, showNotif)
    
    if(wasSent)	println "ROIs successfully sent to OMERO"
    else	println "An issue occurs when trying to send a ROIs to OMERO"

}


//TODO : Load annotations into QuPath
def loadYoloBoxes(yolo_ouput_path){
    def imageWidth = getCurrentImageData().getServer().getWidth()
    def imageHeight = getCurrentImageData().getServer().getHeight()
    
    def yolo_boxes = []
    
    yolo_ouput_path.eachLine { line ->
        def parts = line.split("\\s+")
        if (parts.size() != 5)
            return
        
        //println parts
        def classId = parts[0] as int
        def xCenter = parts[1] as double * imageWidth
        def yCenter = parts[2] as double * imageHeight
        def width = parts[3] as double * imageWidth
        def height = parts[4] as double * imageHeight
    
        def x = xCenter - width / 2
        def y = yCenter - height / 2
    
        def roi = new RectangleROI(x, y, width, height)
        def annotation = PathObjects.createAnnotationObject(roi)
        annotation.setPathClass(getPathClass("Yolo_detection"))
        //detection.setName("Class ${classId}")
        yolo_boxes << annotation
    }
    println yolo_boxes
    return yolo_boxes
    
}


def runYolo(arguments) {
    def envDirPath = "/opt/conda/envs/yolo"
    def cmd = ["bash", "-c"]
    
    // we replace "python" with yolo so it works ! 
    String python_path = envDirPath+separatorChar+"bin"+separatorChar+"yolo";
    List<String> module_args_cmd = new ArrayList<>(Collections.singletonList(python_path));
    module_args_cmd.addAll(arguments);
    
    // convert to a string
    module_args_cmd = module_args_cmd.stream().map(s -> {
        if (s.trim().contains(" "))
            return "\"" + s.trim() + "\"";
        return s;
    }).collect(Collectors.toList());
    // The last part needs to be sent as a single string, otherwise it does not run
    String cmdString = module_args_cmd.toString().replace(",","");
    
    
    // finally add to cmd
    cmd.add( cmdString.substring(1, cmdString.length()-1) );
    
    if (VERBOSE) {
        println cmd
        println "Yolo detection : start"
    }
    try {
        runCommand(cmd)
    } catch(ArrayIndexOutOfBoundsException ex) {
             println(ex.toString());
             println(ex.getMessage());
             println(ex.getStackTrace());  
    } catch(Exception ex) {
             println("Catching the exception");
    }finally {
             println("The final block");
    }
    if (VERBOSE) println "Yolo detection : done"
    
}


def runCommand(cmd) {

    System.out.println(cmd.toString().replace(",", ""))
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)

    Process p = pb.start()

    Thread t = new Thread(Thread.currentThread().getName() + "-" + p.hashCode()) {
        @Override
		void run() {
            BufferedReader stdIn = new BufferedReader(new InputStreamReader(p.getInputStream()))
			try {
                for (String line = stdIn.readLine(); line != null;) {
                    System.out.println(line)
					line = stdIn.readLine()// you don't want to remove or comment that line! no you don't :P
                }
            } catch (IOException e) {
                System.out.println(e.getMessage())
			}
        }
    }
	t.setDaemon(true)
	t.start()

	p.waitFor()

	int exitValue = p.exitValue()

	if (exitValue != 0) {
        System.out.println("Runner  exited with value " + exitValue + ". Please check output above for indications of the problem.")
	} else {
        System.out.println("run finished")
	}
}


import ij.IJ
import static java.io.File.separatorChar
import java.util.stream.Collectors
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.RectangleROI

// SAM imports
import org.elephant.sam.entities.SAMType;
import org.elephant.sam.entities.SAMOutput;
import org.elephant.sam.tasks.SAMDetectionTask;
import qupath.lib.roi.RectangleROI;

// OMERO 
import qupath.ext.biop.servers.omero.raw.*
import qupath.ext.biop.servers.omero.raw.client.*
import qupath.ext.biop.servers.omero.raw.command.*
import qupath.ext.biop.servers.omero.raw.utils.*
import qupath.lib.scripting.QP
