# SWIFT

**S**ingle-organoid **W**orkflow for quantitative **I**maging classi**F**ication and **T**racking 

| | |
|----|----|
|![logo](resources/swift.png) | ![timelapse.gif](resources/Overlay_scaled_timed_legended.gif)|


#  Workflow : EDF - Yolo - SAM - Classifier

- Make an Extended Depth of Field projection (EDF) from z-stack images, in Fiji
- Use Yolo to create bounding box around each organoid
- Use `qupath-extension-sam` to segment each organoid
- Use QuPath Object Classifier to classify organoids in different category

## Workflow

Workflow was tested and validated on [`biop-desktop:0.2.3`](https://biop.github.io/biop-desktop-doc/)

### Make Extended Depth of Field projection, in Fiji
- Start Fiji
- Download the script [0-Fiji-EDF.groovy](scripts/1-Processing/0-EDF-StackProjection/) and open it in the Fiji script editor (drag & drop the script on Fiji main bar)
- Press `Run` , define parameters including sigma

### Detect organoids with Yolo and Segment them with SAM, in Qupath

- Start  QuPath and  SAM for QuPath
- Create a project with `EDF` images generated with Fiji
- Download from gitlabt the script [1-QP-Project_Yolo-Sam.groovy](scripts/1-Processing/1-QP-Project_Yolo-SAM-zoom-zoom.groovy) and open it in the QuPath script editor (drag & drop the script on QuPath main window)
- Define the name of the Yolo model using the `MODEL_NAME` variable (the model is expected to be store in your qupath project in a subfolder `models`)
- Press `Run`

### Train Object Classifier in QuPath
- Manually annotate organoids in different classes
- Train Object Classifier using QuPath menu `Analyze > Train Object Classifier`

### Run Object Classifier in QuPath
- Convert annotations to detections using script [2-QP-Convert_Annotations_to_Detections.groovy](scripts/1-Processing/2-QP-Convert_Annotations_to_Detections.groovy)
- Run Object Classifier using QuPath menu `Analyze > Apply Object Classifier`

## Expected results

| input | EDF | Yolo | SAM |
| ---- | ----| ---- | ---- |
|![input](resources/0-input.png)| ![EDF](resources/1-EDF.png) | ![yolo](resources/2-Yolo.png) | ![SAM](resources/3-SAM.png)|

## Notes 

### Fine-tuning of a Yolo model

- Create a QuPath project with `EDF` images generated with Fiji
- Manually annotate organoids on images
- Using script [1-QP-Export_training_annotation_to_train_YOLO.groovy](scripts\1-Processing\0-YOLO-finetuning\1-QP-Export_training_annotation_to_train_YOLO.groovy) to export images 
- Fine-tune the model using command like : 
```
TRAIN 23 yolo detect train data="pathTodata/data.yaml" model=yolov8s.pt epochs=70 imgsz=1024 augment=true`
```

# Installation 

This workflow has been tested and validated on the docker image `biop/biop-desktop:0.2.3`, with data hosted on an OMERO database or local hardrive.

Plese follow instructions about biop-desktop [installation](https://biop.github.io/biop-desktop-doc/installation.html) and [run](https://biop.github.io/biop-desktop-doc/run.html)

## Software list

| Software | version | Note | 
| ---- | ----| ---- | 
| [Fiji](https://fiji.sc/) | stable | update clij, clijx, clij2, PTBIOP | 
| [QuPath](https://qupath.github.io/) | v0.5.1 | with [QuPath Extensions](https://zenodo.org/records/16744574) | 
| [samapi](https://github.com/ksugar/samapi) | 0.6.1 |  | 
| [YOLO](https://docs.ultralytics.com/) | 8.3.119 |  | 



# TODOs : 
- Script to Classify
- make a QuPath project, containing model and classifier , with data on omero public ? test with Qp0.6.0 ? => issue with number of annotations ! 
