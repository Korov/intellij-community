// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.updater;

import org.jetbrains.annotations.Nls;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SwingUpdaterUI implements UpdaterUI {
  private static final EmptyBorder FRAME_BORDER = new EmptyBorder(8, 8, 8, 8);
  private static final EmptyBorder LABEL_BORDER = new EmptyBorder(0, 0, 5, 0);
  private static final EmptyBorder BUTTONS_BORDER = new EmptyBorder(5, 0, 0, 0);

  private static final Color VALIDATION_ERROR_COLOR = new Color(255, 175, 175);
  private static final Color VALIDATION_CONFLICT_COLOR = new Color(255, 240, 240);

  private final JLabel myProcessTitle;
  private final JProgressBar myProcessProgress;
  private final JLabel myProcessStatus;
  private final JButton myCancelButton;
  private final JFrame myFrame;

  private volatile boolean myCancelled = false;
  private volatile boolean myPaused = false;

  public SwingUpdaterUI() {
    myProcessTitle = new JLabel(" ");
    myProcessProgress = new JProgressBar(0, 100);
    myProcessStatus = new JLabel(" ");

    myCancelButton = new JButton(UpdaterUI.message("button.cancel"));
    myCancelButton.addActionListener(e -> doCancel());

    JPanel processPanel = new JPanel();
    processPanel.setLayout(new BoxLayout(processPanel, BoxLayout.Y_AXIS));
    processPanel.add(myProcessTitle);
    processPanel.add(myProcessProgress);
    processPanel.add(myProcessStatus);
    for (Component each : processPanel.getComponents()) {
      ((JComponent)each).setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    JPanel buttonsPanel = new JPanel();
    buttonsPanel.setBorder(BUTTONS_BORDER);
    buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.X_AXIS));
    buttonsPanel.add(Box.createHorizontalGlue());
    buttonsPanel.add(myCancelButton);

    myFrame = new JFrame();
    myFrame.setTitle(UpdaterUI.message("main.title"));
    myFrame.setLayout(new BorderLayout());
    myFrame.getRootPane().setBorder(FRAME_BORDER);
    myFrame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    myFrame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        doCancel();
      }
    });
    myFrame.add(processPanel, BorderLayout.CENTER);
    myFrame.add(buttonsPanel, BorderLayout.SOUTH);
    myFrame.setMinimumSize(new Dimension(500, 50));
    myFrame.pack();
    myFrame.setLocationRelativeTo(null);
    myFrame.setVisible(true);

    invokeAndWait(() -> {});
  }

  private void doCancel() {
    if (!myCancelled) {
      myPaused = true;
      String message = UpdaterUI.message("confirm.abort");
      int result = JOptionPane.showConfirmDialog(myFrame, message, UpdaterUI.message("main.title"), JOptionPane.YES_NO_OPTION);
      if (result == JOptionPane.YES_OPTION) {
        myCancelled = true;
        myCancelButton.setEnabled(false);
      }
      myPaused = false;
    }
  }

  @Override
  public void setDescription(String text) {
    invokeLater(() -> myProcessTitle.setText(text.isEmpty() ? " " : text));
  }

  @Override
  public void startProcess(String title) {
    invokeLater(() -> {
      myProcessStatus.setText(title);
      myProcessProgress.setIndeterminate(false);
      myProcessProgress.setValue(0);
    });
  }

  @Override
  public void setProgress(int percentage) {
    invokeLater(() -> {
      myProcessProgress.setIndeterminate(false);
      myProcessProgress.setValue(percentage);
    });
  }

  @Override
  public void setProgressIndeterminate() {
    invokeLater(() -> myProcessProgress.setIndeterminate(true));
  }

  @Override
  public void checkCancelled() throws OperationCancelledException {
    while (myPaused) Utils.pause(10);
    if (myCancelled) throw new OperationCancelledException();
  }

  @Override
  public void showError(String message) {
    String html = "<html>" + message.replace("\n", "<br>") + "</html>";
    invokeAndWait(() -> JOptionPane.showMessageDialog(myFrame, html, UpdaterUI.message("error.title"), JOptionPane.ERROR_MESSAGE));
  }

  @Override
  @SuppressWarnings("SSBasedInspection")
  public Map<String, ValidationResult.Option> askUser(List<ValidationResult> validationResults) throws OperationCancelledException {
    boolean canProceed = validationResults.stream().noneMatch(r -> r.options.contains(ValidationResult.Option.NONE));
    Map<String, ValidationResult.Option> result = new HashMap<>();

    invokeAndWait(() -> {
      if (myCancelled) return;

      JDialog dialog = new JDialog(myFrame, UpdaterUI.message("main.title"), true);
      dialog.setLayout(new BorderLayout());
      dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

      JPanel buttonsPanel = new JPanel();
      buttonsPanel.setBorder(BUTTONS_BORDER);
      buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.X_AXIS));
      buttonsPanel.add(Box.createHorizontalGlue());

      JButton cancelButton = new JButton(UpdaterUI.message("button.cancel"));
      cancelButton.addActionListener(e -> {
        myCancelled = true;
        myCancelButton.setEnabled(false);
        dialog.setVisible(false);
      });
      buttonsPanel.add(cancelButton);

      if (canProceed) {
        JButton proceedButton = new JButton(UpdaterUI.message("button.proceed"));
        proceedButton.addActionListener(e -> dialog.setVisible(false));
        buttonsPanel.add(proceedButton);
        dialog.getRootPane().setDefaultButton(proceedButton);
      }
      else {
        dialog.getRootPane().setDefaultButton(cancelButton);
      }

      JTable table = new JTable();
      table.setCellSelectionEnabled(true);
      table.setDefaultEditor(ValidationResult.Option.class, new MyCellEditor());
      table.setDefaultRenderer(Object.class, new MyCellRenderer());

      MyTableModel model = new MyTableModel(validationResults);
      table.setModel(model);
      table.setRowHeight(new JLabel("|").getPreferredSize().height);

      TableColumnModel columnModel = table.getColumnModel();
      for (int i = 0; i < columnModel.getColumnCount(); i++) {
        columnModel.getColumn(i).setPreferredWidth(MyTableModel.getColumnWidth(i, 1000));
      }

      String message = "<html>" + UpdaterUI.message("conflicts.header") + "<br><br>";
      if (canProceed) {
        message += UpdaterUI.message("conflicts.text.1");
      }
      else {
        message += UpdaterUI.message("conflicts.text.2");
      }
      message += "</html>";
      JLabel label = new JLabel(message);
      label.setBorder(LABEL_BORDER);

      dialog.add(label, BorderLayout.NORTH);
      dialog.add(new JScrollPane(table), BorderLayout.CENTER);
      dialog.add(buttonsPanel, BorderLayout.SOUTH);
      dialog.getRootPane().setBorder(FRAME_BORDER);
      dialog.setPreferredSize(new Dimension(1000, 500));
      dialog.pack();
      dialog.setLocationRelativeTo(null);
      dialog.setVisible(true);

      model.collectOptions(result);
    });

    checkCancelled();
    return result;
  }

  @Override
  public String bold(String text) {
    return "<b>" + text + "</b>";
  }

  private static void invokeLater(Runnable runnable) {
    SwingUtilities.invokeLater(runnable);
  }

  private static void invokeAndWait(Runnable runnable) {
    try {
      SwingUtilities.invokeAndWait(runnable);
    }
    catch (InterruptedException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private static class MyTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
      UpdaterUI.message("column.file"), UpdaterUI.message("column.action"), UpdaterUI.message("column.problem"), UpdaterUI.message("column.solution")
    };
    private static final double[] WIDTHS = {0.65, 0.1, 0.15, 0.1};
    private static final int OPTIONS_COLUMN_INDEX = 3;

    private final List<Item> myItems = new ArrayList<>();

    MyTableModel(List<ValidationResult> validationResults) {
      for (ValidationResult each : validationResults) {
        myItems.add(new Item(each, each.options.get(0)));
      }
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return COLUMNS[column];
    }

    public static int getColumnWidth(int column, int totalWidth) {
      return (int)(totalWidth * WIDTHS[column]);
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
      return columnIndex == OPTIONS_COLUMN_INDEX ? ValidationResult.Option.class : super.getColumnClass(columnIndex);
    }

    @Override
    public int getRowCount() {
      return myItems.size();
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
      return columnIndex == OPTIONS_COLUMN_INDEX && getOptions(rowIndex).size() > 1;
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
      if (columnIndex == OPTIONS_COLUMN_INDEX) {
        myItems.get(rowIndex).option = (ValidationResult.Option)value;
      }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      Item item = myItems.get(rowIndex);
      switch (columnIndex) {
        case 0:
          return item.validationResult.path;
        case 1:
          return getActionName(item.validationResult.action);
        case 2:
          return item.validationResult.message;
        case OPTIONS_COLUMN_INDEX:
          return item.option;
      }
      return null;
    }

    private static @Nls String getActionName(ValidationResult.Action action) {
      switch (action) {
        case CREATE: return UpdaterUI.message("action.create");
        case UPDATE: return UpdaterUI.message("action.update");
        case DELETE: return UpdaterUI.message("action.delete");
        case VALIDATE: return UpdaterUI.message("action.validate");
        default: {
          @SuppressWarnings("HardCodedStringLiteral") var name = action.toString();
          return name;
        }
      }
    }

    public ValidationResult.Kind getKind(int rowIndex) {
      return myItems.get(rowIndex).validationResult.kind;
    }

    public List<ValidationResult.Option> getOptions(int rowIndex) {
      Item item = myItems.get(rowIndex);
      return item.validationResult.options;
    }

    public void collectOptions(Map<String, ValidationResult.Option> result) {
      for (Item each : myItems) {
        result.put(each.validationResult.path, each.option);
      }
    }

    private static final class Item {
      private final ValidationResult validationResult;
      private ValidationResult.Option option;

      private Item(ValidationResult validationResult, ValidationResult.Option option) {
        this.validationResult = validationResult;
        this.option = option;
      }
    }
  }

  private static class MyCellEditor extends DefaultCellEditor {
    MyCellEditor() {
      super(new JComboBox<>());
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
      DefaultComboBoxModel<ValidationResult.Option> comboModel = new DefaultComboBoxModel<>();

      for (ValidationResult.Option each : ((MyTableModel)table.getModel()).getOptions(row)) {
        comboModel.addElement(each);
      }

      @SuppressWarnings("unchecked") JComboBox<ValidationResult.Option> comboBox = (JComboBox<ValidationResult.Option>)editorComponent;
      comboBox.setModel(comboModel);

      return super.getTableCellEditorComponent(table, value, isSelected, row, column);
    }
  }

  private static class MyCellRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      Component result = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

      if (!isSelected) {
        ValidationResult.Kind kind = ((MyTableModel)table.getModel()).getKind(row);
        if (kind == ValidationResult.Kind.ERROR) {
          result.setBackground(VALIDATION_ERROR_COLOR);
        }
        else if (kind == ValidationResult.Kind.CONFLICT) {
          result.setBackground(VALIDATION_CONFLICT_COLOR);
        }
      }

      return result;
    }
  }
}
