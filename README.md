# InspectionTest
Intellij Idea inspections test.
Usage: install plugin and launch your intellij idea from cmd with following arguments:<br>
      idea.sh test-inspect <path_to_your_project> <path_to_inspection_profile.xml> -v<verbose_level(1,2,3)> -d <path_to_source_directory> <br>
Optional: <br>
-t <test_classes_root>... -- test classes root directories (only these test classes will be run); if not specified, root directory will be searched in project settings <br>
-m <main_classes_root>...  --  main classes root (needed for test classes); if not specified, root directory will be searched in project settings <br>

Example of usage: <br>
<IDE_HOME>/bin/idea.sh test-inspect ~/MyProject ~/MyProject/.idea/inspectionProfiles/Project_Default.xml -v2 -d ~/MyProject/src/main <br>

If plugin cannot detect classes root, pass directories manually via "-t" and/or "-m" options. <br>
Important! Plugin supports only JUnit tests! 
