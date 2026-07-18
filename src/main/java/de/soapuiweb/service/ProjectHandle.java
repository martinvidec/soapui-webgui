package de.soapuiweb.service;

import com.eviware.soapui.impl.wsdl.WsdlProject;
import de.soapuiweb.storage.ProjectMeta;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Laufzeit-Repräsentation eines geladenen Projekts (Spezifikation 2.1):
 * geladenes Engine-Objekt plus RW-Lock für die Nebenläufigkeitsregeln
 * (Lesen parallel, Mutation/Speichern exklusiv).
 */
public class ProjectHandle {

    private final String id;
    private final WsdlProject project;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile ProjectMeta meta;

    ProjectHandle(String id, WsdlProject project, ProjectMeta meta) {
        this.id = id;
        this.project = project;
        this.meta = meta;
    }

    public String id() {
        return id;
    }

    public WsdlProject project() {
        return project;
    }

    public ProjectMeta meta() {
        return meta;
    }

    void updateMeta(ProjectMeta newMeta) {
        this.meta = newMeta;
    }

    public ReentrantReadWriteLock lock() {
        return lock;
    }
}
