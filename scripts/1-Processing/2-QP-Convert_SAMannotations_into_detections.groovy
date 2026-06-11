 clearDetections()
def pixelSizeMicrons = "2.0"
def classifier_name = "myObjClassifier"

        
 // get annotations "SAM-detection"
sam_annotations = getAnnotationObjects().findAll { it.getPathClass() == getPathClass("SAM-detection")}
//print sam_annotations

if (sam_annotations.isEmpty()) {
    print "No annotation SAM-detection found."
    return
} else {
    //convert annotation to detection objects
    def sam_detections = sam_annotations.collect{ PathObjects.createDetectionObject(it.getROI(), it.getPathClass() ) }
    //print "detections"
    
    currentHierarchy.addObjects(sam_detections)
    
    selectDetections()
    addShapeMeasurements("AREA", "LENGTH", "CIRCULARITY", "SOLIDITY", "MAX_DIAMETER", "MIN_DIAMETER")
    
    selectDetections()
    runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":'+pixelSizeMicrons+',"region":"ROI","tileSizeMicrons":25.0,"channel1":true,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":false,"haralickMin":NaN,"haralickMax":NaN,"haralickDistance":1,"haralickBins":32}')

    // apply classifier
    runObjectClassifier(classifier_name);
    
}
