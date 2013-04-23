/* Copyright CNRS-CREATIS
 *
 * Rafael Ferreira da Silva
 * rafael.silva@creatis.insa-lyon.fr
 * http://www.rafaelsilva.com
 *
 * This software is governed by the CeCILL  license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 */
package fr.insalyon.creatis.gasw.plugin.executor.dirac.execution;

import fr.insalyon.creatis.gasw.GaswConfiguration;
import fr.insalyon.creatis.gasw.GaswException;
import fr.insalyon.creatis.gasw.GaswUtil;
import fr.insalyon.creatis.gasw.bean.Job;
import fr.insalyon.creatis.gasw.dao.DAOException;
import fr.insalyon.creatis.gasw.execution.GaswMonitor;
import fr.insalyon.creatis.gasw.execution.GaswStatus;
import fr.insalyon.creatis.gasw.plugin.executor.dirac.DiracConfiguration;
import fr.insalyon.creatis.gasw.plugin.executor.dirac.DiracConstants;
import fr.insalyon.creatis.gasw.plugin.executor.dirac.bean.JobPool;
import fr.insalyon.creatis.gasw.plugin.executor.dirac.dao.DiracDAOFactory;
import grool.proxy.Proxy;
import grool.proxy.ProxyInitializationException;
import grool.proxy.VOMSExtensionException;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.apache.log4j.Logger;

/**
 *
 * @author Rafael Ferreira da Silva
 */
public class DiracMonitor extends GaswMonitor {

    private static final Logger logger = Logger.getLogger("fr.insalyon.creatis.gasw");
    private static DiracMonitor instance;
    private boolean stop;
    private volatile Map<String, Proxy> monitoredJobs;

    public synchronized static DiracMonitor getInstance() throws GaswException {

        if (instance == null) {
            instance = new DiracMonitor();
            instance.start();
        }
        return instance;
    }

    private DiracMonitor() throws GaswException {

        super();
        stop = false;
        this.monitoredJobs = new HashMap<String, Proxy>();
        if (GaswConfiguration.getInstance().isMinorStatusEnabled()) {
            DiracMinorStatusServiceMonitor.getInstance();
        }
    }

    @Override
    public void run() {
        Process process = null;

        while (!stop) {
            try {
                verifySignaledJobs();

                List<Job> jobsList = jobDAO.getActiveJobs();

                if (!jobsList.isEmpty()) {

                    List<String> command = new ArrayList<String>();
                    command.add("dirac-wms-job-status");

                    for (Job job : jobsList) {
                        command.add(job.getId());
                    }

                    Proxy userProxy = monitoredJobs.get(jobsList.get(0).getId());
                    process = GaswUtil.getProcess(logger, userProxy,
                            command.toArray(new String[]{}));

                    BufferedReader br = GaswUtil.getBufferedReader(process);

                    StringBuilder cout = new StringBuilder();
                    String s = null;

                    while ((s = br.readLine()) != null) {

                        if (s.contains("JobID=")) {

                            String[] res = s.split(" ");
                            cout.append(s).append("\n");

                            Job job = jobDAO.getJobByID(res[0].replace("JobID=", ""));
                            DiracStatus status = DiracStatus.valueOf(res[1].replace("Status=", "").replace(";", ""));

                            if (status == DiracStatus.Running && job.getStatus() != GaswStatus.RUNNING) {

                                job.setStatus(GaswStatus.RUNNING);
                                job.setDownload(new Date());
                                updateStatus(job);

                            } else if (status == DiracStatus.Waiting && job.getStatus() != GaswStatus.QUEUED) {

                                job.setStatus(GaswStatus.QUEUED);
                                job.setQueued(new Date());
                                updateStatus(job);

                            } else if (status == DiracStatus.Received && job.getStatus() != GaswStatus.SUCCESSFULLY_SUBMITTED) {

                                job.setStatus(GaswStatus.SUCCESSFULLY_SUBMITTED);
                                updateStatus(job);

                            } else if (!isReplica(job)) {

                                boolean finished = true;

                                switch (status) {
                                    case Done:
                                        job.setStatus(GaswStatus.COMPLETED);
                                        break;
                                    case Failed:
                                        job.setStatus(GaswStatus.ERROR);
                                        break;
                                    case Killed:
                                        job.setStatus(GaswStatus.CANCELLED);
                                        break;
                                    case Stalled:
                                        job.setStatus(GaswStatus.STALLED);
                                        break;
                                    default:
                                        finished = false;
                                }

                                if (finished) {
                                    updateStatus(job);
                                    logger.info("Dirac Monitor: job \"" + job.getId() + "\" finished as \"" + status + "\"");

                                    new DiracOutputParser(job.getId(), monitoredJobs.get(job.getId())).start();

                                    if (job.getStatus() == GaswStatus.COMPLETED) {
                                        killReplicas(job.getInvocationID());
                                    }
                                }
                            }
                        }
                    }
                    br.close();
                    process.waitFor();
                    if (process.exitValue() != 0) {
                        logger.error(cout);
                    }
                }
                Thread.sleep(GaswConfiguration.getInstance().getDefaultSleeptime());
            } catch (IOException ex) {
                logger.error(ex);
            } catch (ProxyInitializationException ex) {
                logger.error(ex);
            } catch (VOMSExtensionException ex) {
                logger.error(ex);
            } catch (GaswException ex) {
                // do nothing
            } catch (DAOException ex) {
                // do nothing
            } catch (InterruptedException ex) {
                logger.error(ex);
            } finally {
                closeProcess(process);
            }
        }

    }

    @Override
    public synchronized void add(String jobID, String symbolicName, String fileName,
            String parameters, Proxy userProxy) throws GaswException {

        add(new Job(jobID, GaswConfiguration.getInstance().getSimulationID(),
                GaswStatus.SUCCESSFULLY_SUBMITTED, symbolicName, fileName,
                parameters, DiracConstants.EXECUTOR_NAME));
        if (userProxy != null) {
            this.monitoredJobs.put(jobID, userProxy);
        }
    }

    @Override
    protected void kill(String jobID) {
        Process process = null;
        try {
            process = GaswUtil.getProcess(logger, "dirac-wms-job-kill", jobID);
            process.waitFor();

            BufferedReader br = GaswUtil.getBufferedReader(process);
            String cout = "";
            String s = null;
            while ((s = br.readLine()) != null) {
                cout += s;
            }
            br.close();

            if (process.exitValue() != 0) {
                logger.error(cout);
            } else {
                logger.info("Killed DIRAC Job ID '" + jobID + "'");
            }
        } catch (IOException ex) {
            logger.error(ex);
        } catch (InterruptedException ex) {
            logger.error(ex);
        } finally {
            closeProcess(process);
        }
    }

    @Override
    protected void reschedule(String jobID) {
        Process process = null;
        try {
            process = GaswUtil.getProcess(logger, "dirac-wms-job-reschedule", jobID);
            process.waitFor();

            BufferedReader br = GaswUtil.getBufferedReader(process);
            String cout = "";
            String s = null;
            while ((s = br.readLine()) != null) {
                cout += s;
            }
            br.close();

            Job job = jobDAO.getJobByID(jobID);
            if (process.exitValue() != 0) {
                logger.error(cout);
            } else {
                job.setStatus(GaswStatus.SUCCESSFULLY_SUBMITTED);
                jobDAO.update(job);
                logger.info("Rescheduled DIRAC Job ID '" + jobID + "'.");
            }
        } catch (DAOException ex) {
            // do nothing
        } catch (IOException ex) {
            logger.error(ex);
        } catch (InterruptedException ex) {
            logger.error(ex);
        } finally {
            closeProcess(process);
        }
    }

    @Override
    protected void replicate(String jobID) {

        try {
            Job job = jobDAO.getJobByID(jobID);
            logger.info("Replicating: " + job.getId() + " - " + job.getFileName());
            DiracDAOFactory.getInstance().getJobPoolDAO().add(
                    new JobPool(job.getFileName(), job.getCommand(), job.getParameters()));

        } catch (DAOException ex) {
            // do nothing
        }
    }

    @Override
    protected void killReplicas(int invocationID) {

        try {
            for (Job job : jobDAO.getActiveJobsByInvocationID(invocationID)) {
                logger.info("Killing replica: " + job.getId() + " - " + job.getFileName());
                kill(job.getId());
            }

        } catch (DAOException ex) {
            // do nothing
        }
    }

    /**
     * Terminates DIRAC monitor thread.
     *
     * @throws GaswException
     */
    public synchronized void terminate() throws GaswException {

        stop = true;
        if (DiracConfiguration.getInstance().isNotificationEnabled()) {
            DiracMinorStatusServiceMonitor.getInstance().terminate();
        }
        instance = null;
    }

    /**
     * Closes a process.
     *
     * @param process
     * @throws IOException
     */
    private void closeProcess(Process process) {
        if (process != null) {
            try {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            } catch (IOException ex) {
                logger.error(ex);
            }
        }
        process = null;
    }
}
