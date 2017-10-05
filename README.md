
# Corda FTP

Based on the Corda template (so don't be surprised if there are still some artifacts hidden).

## Background

This is a very basic demonstration of using Corda to transfer files from one node to another. It is best run via IntelliJ but can be run via the command line as well. There are two parts to the demonstration; the Corda Nodes and the client application that scans directories and sends the files to the other nodes. In this demonstration, we will be using a pre-configured set of nodes and directories but once you have it running, feel free to expand this and let us know how you get on.

## Instructions.

1. Clone the repo from https://github.com/ragmondo/cordaftp
2. Open this directory as a project in IntelliJ
3. Start the "Run Corda FTP Nodes" run configuration in the dropdown
4. Wait until the nodes have started up (i.e. there is no more activity in the output window)
5. Start the "SenderKt" program
6. Using the command shell or GUI explorer, navigate to the directory under build called "send1"
7. Create a file that ends in a .txt suffix with some data
8. Watch the output of either of the running sessions and wait...
9. The file will disappear from that directory / node and appear in another directory being transferred via the Corda network that you are running. The destination directory will appear under build/DATETIMEOFBUILD/CorpA/incoming_1


It's a very simple example of a CorDapp using attachments but do give us feedback on how you get on and feel free to clone and PR any modifications you make.




